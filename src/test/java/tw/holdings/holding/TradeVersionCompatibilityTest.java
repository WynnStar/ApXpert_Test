package tw.holdings.holding;

import java.math.BigDecimal;
import java.time.LocalDate;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:app-v1-api-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class TradeVersionCompatibilityTest {
    private static final String V1 = "/api/v1/holdings";
    private static final String V2 = "/api/v2/holdings";
    private static final String OLD_BUY = """
            {"code":"0050","lots":2,"buy_price":150}
            """;
    private static final String NEW_BUY = """
            {"side":"BUY","trade_date":"2026-11-17","shares":300,"unit_price":160,"confirm_v2":true}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean QuoteService quotes;

    @BeforeEach
    void setup() {
        doAnswer(invocation -> quotes.lookup(invocation.getArgument(0), invocation.getArgument(1)))
                .when(quotes).forDisplay(org.mockito.ArgumentMatchers.anyString(), nullable(Market.class));
        jdbc.update("DELETE FROM holding_trades");
        jdbc.update("DELETE FROM holdings");
        fixedQuote("0050", "元大台灣50", "170.00");
        fixedQuote("2330", "台積電", "120.00");
    }

    @Test
    void legacyWritesContinueToChangeOpeningPositionUntilConversion() throws Exception {
        long id = createLegacy(OLD_BUY);
        mvc.perform(get(V2 + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shares").value(2000))
                .andExpect(jsonPath("$.remaining_cost").value(300427))
                .andExpect(jsonPath("$.v2_managed").value(false));

        mvc.perform(put(V1 + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lots\":3,\"buy_price\":151.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lots").value(3));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].side").value("OPENING"))
                .andExpect(jsonPath("$[0].trade_date").hasJsonPath())
                .andExpect(jsonPath("$[0].trade_date").value(nullValue()))
                .andExpect(jsonPath("$[0].shares").value(3000))
                .andExpect(jsonPath("$[0].unit_price").value(151.50));
        mvc.perform(delete(V1 + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(V2 + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void conversionIsPerHoldingAndFailedSaleLeavesLegacyWritesAvailable() throws Exception {
        long id = createLegacy(OLD_BUY);
        long other = createLegacy(OLD_BUY.replace("0050", "2330"));
        String trades = V2 + "/" + id + "/trades";
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_BUY.replace("true", "false")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIRM_V2_REQUIRED"));

        String sale = """
                {"side":"SELL","trade_date":"2026-11-17","shares":2301,"unit_price":170,"confirm_v2":true}
                """;
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(sale))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_SHARES"));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.lots").value(2));
        mvc.perform(get(trades)).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/v2/holdings/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("App 1.0 的清單與總計將不包含它")));
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(NEW_BUY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shares").value(2300))
                .andExpect(jsonPath("$.v2_managed").value(true));
        mvc.perform(get("/v2/holdings/" + id)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("App 1.0 的清單與總計將不包含它"))));

        mvc.perform(get(V1)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(other));
        mvc.perform(get("/api/v1/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_cost").value(300427));
        mvc.perform(delete(V1 + "/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HOLDING_REQUIRES_V2"));
        mvc.perform(put(V1 + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lots\":3,\"buy_price\":151.50}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HOLDING_REQUIRES_V2"));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isConflict());
        mvc.perform(post(V1).contentType(MediaType.APPLICATION_JSON).content(OLD_BUY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DUPLICATE_CODE"))
                .andExpect(jsonPath("$.error.message", containsString("2.0")));

        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON)
                        .content(sale.replace("2301", "400").replace("true", "false")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shares").value(1900))
                .andExpect(jsonPath("$.realized_pnl").value(7228.17));
        mvc.perform(get(trades)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].side").value("SELL"));
        mvc.perform(get("/v2/holdings/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"profit-positive\">+7,228.17 元")));
    }

    @Test
    void newWebHoldingSupportsSharesAndOnlyAppearsInV2() throws Exception {
        mvc.perform(get("/v2/holdings")).andExpect(status().isOk())
                .andExpect(content().string(containsString("目前沒有持股。")));
        mvc.perform(post("/v2/holdings").param("code", "2330")
                        .param("tradeDate", "2026-11-17").param("shares", "1500").param("unitPrice", "100"))
                .andExpect(status().is3xxRedirection());
        var response = mvc.perform(get(V2)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shares").value(1500))
                .andExpect(jsonPath("$[0].average_cost").value(100.142))
                .andExpect(jsonPath("$[0].display_average_cost").doesNotExist())
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsByteArray()).get(0).get("id").longValue();
        mvc.perform(get("/v2/holdings")).andExpect(content().string(containsString("<td>100.14</td>")));
        mvc.perform(get("/v2/holdings/" + id)).andExpect(content().string(containsString("<dd>100.14</dd>")));
        mvc.perform(get(V1)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void webListKeepsColumnOrderAndShowsRateColorsAndMissingQuoteTotals() throws Exception {
        when(quotes.lookup(eq("6488"), nullable(Market.class))).thenReturn(new QuoteResult(
                QuoteResult.Status.FOUND, "環球晶", Market.TPEX, null, null));
        long positive = createLegacy(OLD_BUY);
        long negative = createLegacy(OLD_BUY.replace("0050", "2330"));
        long missing = createLegacy(OLD_BUY.replace("0050", "6488"));

        mvc.perform(get("/css/trade-profit.css")).andExpect(status().isOk())
                .andExpect(content().string(containsString(".profit-positive { color: #b42318; }")))
                .andExpect(content().string(containsString(".profit-negative { color: #08783e; }")));
        mvc.perform(get("/v2/holdings/" + positive)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/css/trade-profit.css\"")))
                .andExpect(content().string(containsString("class=\"profit-positive\">+38,749.00 元")))
                .andExpect(content().string(containsString("<span>0.00 元</span>")));
        mvc.perform(get("/v2/holdings/" + negative)).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"profit-negative\">-61,489.00 元")));
        mvc.perform(get("/v2/holdings/" + missing)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<span>未取得</span>")));

        var result = mvc.perform(get("/v2/holdings")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>上市</td>")))
                .andExpect(content().string(containsString("<td>上櫃</td>")))
                .andExpect(content().string(containsString("<td>2026-11-16</td>")))
                .andExpect(content().string(containsString("class=\"profit-positive\">+38,749.00 元")))
                .andExpect(content().string(containsString("class=\"profit-negative\">-61,489.00 元")))
                .andExpect(content().string(containsString("class=\"profit-positive\">+12.90%")))
                .andExpect(content().string(containsString("class=\"profit-negative\">-2.52%")))
                .andExpect(content().string(containsString("總已實現損益")))
                .andExpect(content().string(containsString("901,281.00 元")))
                .andExpect(content().string(containsString("580,000.00 元")))
                .andExpect(content().string(containsString("有 1 筆持股未取得收盤價")))
                .andReturn();
        String page = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int cursor = page.indexOf("持股總計");
        for (String label : new String[]{"股票代號", "股票名稱", "市場", "股數", "每股平均成本（含費）",
                "收盤價", "收盤日期", "未實現損益", "收益率", "已實現損益", "操作"}) {
            int next = page.indexOf("<th>" + label + "</th>", cursor);
            org.junit.jupiter.api.Assertions.assertTrue(next > cursor, label);
            cursor = next;
        }
        org.junit.jupiter.api.Assertions.assertTrue(page.matches("(?s).*<td>6488</td>.*?未取得.*?未取得.*?未取得.*"));
    }

    @Test
    void closedHoldingKeepsItsQuoteWithoutAddingMarketValueOrMissingQuoteCount() throws Exception {
        long id = createLegacy(OLD_BUY);
        mvc.perform(post(V2 + "/" + id + "/trades").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"side":"SELL","trade_date":"2026-11-17","shares":2000,
                                 "unit_price":160,"confirm_v2":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shares").value(0))
                .andExpect(jsonPath("$.current_price").value(170.00))
                .andExpect(jsonPath("$.price_date").value("2026-11-16"))
                .andExpect(jsonPath("$.remaining_cost").value(0))
                .andExpect(jsonPath("$.realized_pnl").value(18797))
                .andExpect(jsonPath("$.unrealized_pnl").value(0));
        mvc.perform(get(V2)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shares").value(0))
                .andExpect(jsonPath("$[0].price_date").value("2026-11-16"));
        mvc.perform(get("/v2/holdings")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>170.00</td>")))
                .andExpect(content().string(containsString("<td>2026-11-16</td>")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<dt>總市值</dt>\\s*<dd>0.00 元</dd>.*")));

        when(quotes.lookup("0050", Market.TWSE)).thenReturn(
                new QuoteResult(QuoteResult.Status.UNAVAILABLE, null, null, null, null));
        mvc.perform(get("/v2/holdings")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("筆持股未取得收盤價"))));
    }

    @Test
    void futureDatesAreRejectedAndSameDayBuyBeforeSellWorks() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        String future = today.plusDays(1).toString();
        String buy = """
                {"code":"2330","trade_date":"%s","shares":300,"unit_price":100}
                """;
        mvc.perform(get("/v2/holdings")).andExpect(status().isOk())
                .andExpect(content().string(containsString("max=\"" + today + "\"")));
        mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(buy.formatted(future)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("交易日期不能晚於今天"));
        mvc.perform(post("/v2/holdings").param("code", "2330")
                        .param("tradeDate", future).param("shares", "300").param("unitPrice", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("交易日期不能晚於今天")));
        mvc.perform(get(V2)).andExpect(content().json("[]"));

        var created = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(buy.formatted(today)))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();
        String trades = V2 + "/" + id + "/trades";
        String detail = "/v2/holdings/" + id;
        String sell = """
                {"side":"SELL","trade_date":"%s","shares":100,"unit_price":110}
                """;
        mvc.perform(get(detail)).andExpect(status().isOk())
                .andExpect(content().string(containsString("max=\"" + today + "\"")));
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(sell.formatted(future)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post(detail + "/trades").param("side", "SELL")
                        .param("tradeDate", future).param("shares", "100").param("unitPrice", "110"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("交易日期不能晚於今天")));
        mvc.perform(get(trades)).andExpect(jsonPath("$.length()").value(1));

        var past = today.minusDays(1);
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(sell.formatted(past)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_SHARES"))
                .andExpect(jsonPath("$.error.message", containsString("請先補登過往的買進紀錄")));
        mvc.perform(post(detail + "/trades").param("side", "SELL")
                        .param("tradeDate", past.toString()).param("shares", "100").param("unitPrice", "110"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("請先補登過往的買進紀錄")));
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(sell.formatted(today)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shares").value(200));
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON)
                        .content(sell.formatted(today).replace("\"shares\":100", "\"shares\":201")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message", not(containsString("請先補登"))));
        mvc.perform(get(trades)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void webReordersSameDayTradesAndRejectsOrdersThatWouldOversell() throws Exception {
        var created = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"2330","trade_date":"2026-11-16","shares":100,"unit_price":10}
                """)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();
        String api = V2 + "/" + id;
        String page = "/v2/holdings/" + id;
        String trades = api + "/trades";

        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"BUY","trade_date":"2026-11-16","shares":100,"unit_price":20}
                """)).andExpect(status().isCreated());
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"SELL","trade_date":"2026-11-16","shares":100,"unit_price":30}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remaining_cost").value(1520))
                .andExpect(jsonPath("$.realized_pnl").value(1451));
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"BUY","trade_date":"2026-11-17","shares":100,"unit_price":40}
                """)).andExpect(status().isCreated());

        long sellId = jdbc.queryForObject("SELECT id FROM holding_trades WHERE holding_id = ? AND side = 'SELL'",
                Long.class, id);
        String move = page + "/trades/" + sellId + "/move";
        mvc.perform(get(page)).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"" + move + "\"")))
                .andExpect(content().string(containsString(">上移</button>")))
                .andExpect(content().string(containsString(">下移</button>")));
        mvc.perform(post(move).param("direction", "DOWN")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("orderError", containsString("同一天")));
        mvc.perform(post(move).param("direction", "UP")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", "已調整同日交易順序"));
        var rejected = mvc.perform(post(move).param("direction", "UP")).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("orderError", containsString("調整後會發生超賣"))).andReturn();
        mvc.perform(get(page).flashAttrs(rejected.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("調整後會發生超賣")));

        mvc.perform(get(trades)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unit_price").value(10))
                .andExpect(jsonPath("$[1].side").value("SELL"))
                .andExpect(jsonPath("$[2].unit_price").value(20))
                .andExpect(jsonPath("$[3].unit_price").value(40))
                .andExpect(jsonPath("$[1].id").doesNotExist())
                .andExpect(jsonPath("$[1].can_move_up").doesNotExist());
        mvc.perform(get(api)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shares").value(200))
                .andExpect(jsonPath("$.remaining_cost").value(6040))
                .andExpect(jsonPath("$.realized_pnl").value(1951));

        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"BUY","trade_date":"2026-11-16","shares":100,"unit_price":21}
                """)).andExpect(status().isCreated());
        mvc.perform(get(trades)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].side").value("SELL"))
                .andExpect(jsonPath("$[2].unit_price").value(20))
                .andExpect(jsonPath("$[3].unit_price").value(21))
                .andExpect(jsonPath("$[4].unit_price").value(40));
    }

    private long createLegacy(String body) throws Exception {
        var result = mvc.perform(post(V1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("id").longValue();
    }

    private void fixedQuote(String code, String name, String close) {
        when(quotes.lookup(eq(code), nullable(Market.class))).thenReturn(new QuoteResult(
                QuoteResult.Status.FOUND, name, Market.TWSE, new BigDecimal(close), LocalDate.of(2026, 11, 16)));
    }
}
