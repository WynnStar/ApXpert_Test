package tw.holdings.holding.v2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tw.holdings.quote.Market;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:v2-api-parity;DB_CLOSE_DELAY=-1",
        "quotes.market-status-sync=false", "quotes.warmup-enabled=false", "quotes.calendar-sync-enabled=false"})
@AutoConfigureMockMvc
class TradeApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean QuoteService quotes;

    private LocalDate today;

    @BeforeEach
    void setup() {
        today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        jdbc.update("DELETE FROM market_final_close");
        jdbc.update("DELETE FROM market_event");
        jdbc.update("DELETE FROM market_identity");
        jdbc.update("DELETE FROM holding_trades");
        jdbc.update("DELETE FROM holdings");
        fixedQuote("0050", "元大台灣50", "170.00");
        fixedQuote("2330", "台積電", "12.00");
    }

    @Test
    void emptySummaryUsesSnakeCaseAndNullRates() throws Exception {
        mvc.perform(get("/api/v2/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining_cost").value(0))
                .andExpect(jsonPath("$.market_value").value(0))
                .andExpect(jsonPath("$.unrealized_pnl").value(0))
                .andExpect(jsonPath("$.realized_pnl").value(0))
                .andExpect(jsonPath("$.quote_missing").value(0))
                .andExpect(jsonPath("$.delisted_cost").value(0))
                .andExpect(jsonPath("$.unrealized_return_pct").value(nullValue()))
                .andExpect(jsonPath("$.realized_return_pct").value(nullValue()))
                .andExpect(jsonPath("$.remainingCost").doesNotExist());
    }

    @Test
    void summaryCombinesRemainingCostAndRealizedAndUnrealizedProfit() throws Exception {
        create("2330", 1_000, "10.00");
        long etf = create("0050", 300, "150.00");
        appendSale(etf, 100, "160.00");

        mvc.perform(get("/api/v2/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining_cost").value(closeTo(40062.67, 0.001)))
                .andExpect(jsonPath("$.market_value").value(closeTo(46000, 0.001)))
                .andExpect(jsonPath("$.unrealized_pnl").value(closeTo(5799.33, 0.001)))
                .andExpect(jsonPath("$.unrealized_return_pct").value(closeTo(14.48, 0.001)))
                .andExpect(jsonPath("$.realized_pnl").value(closeTo(940.67, 0.001)))
                .andExpect(jsonPath("$.realized_return_pct").value(closeTo(6.26, 0.001)))
                .andExpect(jsonPath("$.quote_missing").value(0));
        mvc.perform(get("/api/v2/holdings/" + etf)).andExpect(status().isOk())
                .andExpect(jsonPath("$.realized_return_pct").value(closeTo(6.26, 0.001)))
                .andExpect(jsonPath("$.unrealized_return_pct").value(closeTo(12.90, 0.001)));
    }

    @Test
    void missingQuotesAndClosedPositionsKeepTheirSummaryMeaning() throws Exception {
        long id = create("0050", 300, "150.00");
        when(quotes.forDisplay(eq("0050"), nullable(Market.class))).thenReturn(QuoteResult.unavailable());
        mvc.perform(get("/api/v2/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining_cost").value(closeTo(45064, 0.001)))
                .andExpect(jsonPath("$.market_value").value(0))
                .andExpect(jsonPath("$.unrealized_pnl").value(0))
                .andExpect(jsonPath("$.quote_missing").value(1));
        mvc.perform(get("/api/v2/holdings/" + id))
                .andExpect(jsonPath("$.unrealized_return_pct").value(nullValue()));

        appendSale(id, 300, "160.00");
        mvc.perform(get("/api/v2/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining_cost").value(0))
                .andExpect(jsonPath("$.realized_pnl").value(closeTo(2820, 0.001)))
                .andExpect(jsonPath("$.realized_return_pct").value(closeTo(6.26, 0.001)))
                .andExpect(jsonPath("$.unrealized_return_pct").value(nullValue()))
                .andExpect(jsonPath("$.quote_missing").value(0));
    }

    @Test
    void deletingHoldingRemovesAllItsTradesAndLeavesOthers() throws Exception {
        long removed = create("0050", 300, "150.00");
        appendSale(removed, 100, "160.00");
        long retained = create("2330", 1_000, "10.00");

        mvc.perform(delete("/api/v2/holdings/" + removed)).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM holding_trades WHERE holding_id = ?", Integer.class, removed));
        mvc.perform(get("/api/v2/holdings/" + removed)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v2/holdings/" + retained)).andExpect(status().isOk());
        mvc.perform(get("/api/v2/summary"))
                .andExpect(jsonPath("$.remaining_cost").value(closeTo(10020, 0.001)))
                .andExpect(jsonPath("$.realized_pnl").value(0));
        mvc.perform(delete("/api/v2/holdings/" + removed)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void v2CanDeleteLegacyHoldingWithoutRequiringConversion() throws Exception {
        long id = createLegacy();
        mvc.perform(delete("/api/v2/holdings/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/holdings/" + id)).andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM holding_trades WHERE holding_id = ?", Integer.class, id));
    }

    @Test
    void returnedTradeIdAllowsEditingWithoutCreatingAnotherTrade() throws Exception {
        long id = create("0050", 300, "150.00");
        long tradeId = firstTradeId(id);
        mvc.perform(put("/api/v2/holdings/" + id + "/trades/" + tradeId)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(today, 200, "140.00", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shares").value(200))
                .andExpect(jsonPath("$.remaining_cost").value(closeTo(28039, 0.001)));
        mvc.perform(get("/api/v2/holdings/" + id + "/trades"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(tradeId))
                .andExpect(jsonPath("$[0].shares").value(200))
                .andExpect(jsonPath("$[0].side").value("BUY"));
    }

    @Test
    void editThatWouldOversellRollsBackWithoutChangingTrades() throws Exception {
        long id = create("0050", 300, "150.00");
        appendSale(id, 100, "160.00");
        long buyId = firstTradeId(id);

        mvc.perform(put("/api/v2/holdings/" + id + "/trades/" + buyId)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(today, 99, "150.00", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_SHARES"));
        mvc.perform(get("/api/v2/holdings/" + id)).andExpect(jsonPath("$.shares").value(200))
                .andExpect(jsonPath("$.remaining_cost").value(closeTo(30042.67, 0.001)));
        mvc.perform(get("/api/v2/holdings/" + id + "/trades"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].shares").value(300));
    }

    @Test
    void legacyOpeningEditRequiresConfirmationAndKeepsExplicitV1Conflicts() throws Exception {
        long id = createLegacy();
        long openingId = firstTradeId(id);
        String path = "/api/v2/holdings/" + id + "/trades/" + openingId;
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(updateBody(null, 300, "10.00", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIRM_V2_REQUIRED"));
        mvc.perform(get("/api/v1/holdings/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lots").value(1));

        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(updateBody(null, 300, "10.00", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.v2_managed").value(true))
                .andExpect(jsonPath("$.shares").value(300));
        mvc.perform(get("/api/v1/holdings/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HOLDING_REQUIRES_V2"));
        mvc.perform(delete("/api/v1/holdings/" + id)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/holdings/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lots\":1,\"buy_price\":10}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/holdings")).andExpect(content().json("[]"));
    }

    @Test
    void foreignOrMissingTradeIdsReturnStructured404() throws Exception {
        long first = create("0050", 300, "150.00");
        long second = create("2330", 1_000, "10.00");
        long foreignTradeId = firstTradeId(first);
        mvc.perform(put("/api/v2/holdings/" + second + "/trades/" + foreignTradeId)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(today, 100, "10.00", false)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(put("/api/v2/holdings/" + first + "/trades/999999999")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody(today, 100, "10.00", false)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void invalidEditNumbersAndDatesReturn400WithoutChangingThePosition() throws Exception {
        long id = create("0050", 300, "150.00");
        String path = "/api/v2/holdings/" + id + "/trades/" + firstTradeId(id);
        for (String body : new String[] {
                updateBody(today, 0, "150.00", false),
                updateBody(today, 300, "150.001", false),
                updateBody(today.plusDays(1), 300, "150.00", false),
                updateBody(null, 300, "150.00", false),
                updateBody(today, 300, "150.00", false).replace("\"shares\":300", "\"shares\":1.5")}) {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(get("/api/v2/holdings/" + id)).andExpect(jsonPath("$.shares").value(300));
    }

    @Test
    void bothVersionsKeepUnknownStockDistinctFromUnavailableIdentity() throws Exception {
        when(quotes.lookup(eq("9999"), nullable(Market.class))).thenReturn(QuoteResult.notFound());
        when(quotes.lookup(eq("9998"), nullable(Market.class))).thenReturn(QuoteResult.unavailable());
        for (String version : new String[] {"v1", "v2"}) {
            String base = "/api/" + version + "/holdings";
            String input = version.equals("v1") ? "{\"code\":\"9999\",\"lots\":1,\"buy_price\":10}"
                    : "{\"code\":\"9999\",\"trade_date\":\"" + today + "\",\"shares\":100,\"unit_price\":10}";
            mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"));
            mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(input.replace("9999", "9998")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("QUOTE_UNAVAILABLE"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM holdings", Integer.class));
    }

    private long create(String code, long shares, String price) throws Exception {
        String body = "{\"code\":\"" + code + "\",\"trade_date\":\"" + today
                + "\",\"shares\":" + shares + ",\"unit_price\":" + price + "}";
        var result = mvc.perform(post("/api/v2/holdings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private long createLegacy() throws Exception {
        var result = mvc.perform(post("/api/v1/holdings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"2330\",\"lots\":1,\"buy_price\":10}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private void appendSale(long id, long shares, String price) throws Exception {
        String body = "{\"side\":\"SELL\",\"trade_date\":\"" + today + "\",\"shares\":" + shares + ",\"unit_price\":" + price + "}";
        mvc.perform(post("/api/v2/holdings/" + id + "/trades").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private long firstTradeId(long id) throws Exception {
        var result = mvc.perform(get("/api/v2/holdings/" + id + "/trades")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get(0).path("id").asLong();
    }

    private String updateBody(LocalDate date, long shares, String price, boolean confirm) {
        String dateValue = date == null ? "null" : "\"" + date + "\"";
        return "{\"trade_date\":" + dateValue + ",\"shares\":" + shares
                + ",\"unit_price\":" + price + ",\"confirm_v2\":" + confirm + "}";
    }

    private void fixedQuote(String code, String name, String price) {
        var quote = QuoteResult.found(name, Market.TWSE, new BigDecimal(price), today);
        when(quotes.lookup(eq(code), nullable(Market.class))).thenReturn(quote);
        when(quotes.forDisplay(eq(code), nullable(Market.class))).thenReturn(quote);
    }
}
