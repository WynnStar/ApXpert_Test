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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:app-v1-api-test;DB_CLOSE_DELAY=-1",
        "quotes.market-status-sync=false"})
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
        jdbc.update("DELETE FROM market_final_close");
        jdbc.update("DELETE FROM market_event");
        jdbc.update("DELETE FROM market_identity");
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

    @Test
    void v2ListCanDeleteAnUnconvertedLegacyHoldingAfterConfirmation() throws Exception {
        long id = createLegacy(OLD_BUY);
        String list = "/v2/holdings";
        String deletePage = list + "/" + id + "/delete";
        mvc.perform(get(list)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + deletePage + "\">刪除")));
        mvc.perform(get(deletePage)).andExpect(status().isOk())
                .andExpect(content().string(containsString("確認刪除 0050 元大台灣50")))
                .andExpect(content().string(containsString("所有買賣紀錄和已實現損益紀錄")))
                .andExpect(content().string(containsString("href=\"" + list + "\">取消")))
                .andExpect(content().string(containsString("action=\"" + deletePage + "\"")));

        // 取消連結只返回清單，確認頁與取消都不會刪除資料。
        mvc.perform(get(list)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<td>0050</td>")));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isOk());
        mvc.perform(get(V2 + "/" + id + "/trades"))
                .andExpect(jsonPath("$[0].side").value("OPENING"));

        var deleted = mvc.perform(post(deletePage)).andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(list))
                .andReturn();
        mvc.perform(get(list).flashAttrs(deleted.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("已刪除整檔持股及交易紀錄")))
                .andExpect(content().string(containsString("目前沒有持股。")));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/summary")).andExpect(jsonPath("$.total_cost").value(0));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(status().isNotFound());
        mvc.perform(get(deletePage)).andExpect(status().isNotFound());
        mvc.perform(post(deletePage)).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM holding_trades WHERE holding_id = ?", Long.class, id));
    }

    @Test
    void v2ListDeletesClosedHoldingAndItsProfitWithoutChangingOtherHoldings() throws Exception {
        long id = createLegacy(OLD_BUY);
        long other = createLegacy(OLD_BUY.replace("0050", "2330"));
        mvc.perform(post(V2 + "/" + id + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"SELL","trade_date":"2026-11-17","shares":2000,
                 "unit_price":160,"confirm_v2":true}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.shares").value(0))
                .andExpect(jsonPath("$.realized_pnl").value(18797));
        mvc.perform(delete(V1 + "/" + id)).andExpect(status().isConflict());
        String list = "/v2/holdings";
        String deletePage = list + "/" + id + "/delete";
        mvc.perform(get(list)).andExpect(status().isOk())
                .andExpect(content().string(containsString("18,797.00 元")))
                .andExpect(content().string(containsString("href=\"" + deletePage + "\">刪除")));
        mvc.perform(post(deletePage)).andExpect(status().is3xxRedirection());
        mvc.perform(get(list)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("18,797.00 元"))))
                .andExpect(content().string(not(containsString("<td>0050</td>"))))
                .andExpect(content().string(containsString("<td>2330</td>")));
        mvc.perform(get(V1)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(other));
        mvc.perform(get(V2 + "/" + other + "/trades")).andExpect(jsonPath("$.length()").value(1));
        org.junit.jupiter.api.Assertions.assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM holding_trades WHERE holding_id = ?", Long.class, id));
    }

    @Test
    void deletingASaleKeepsOtherTradesAndRecalculatesThePosition() throws Exception {
        var created = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"2330","trade_date":"2026-11-16","shares":100,"unit_price":10}
                """)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();
        String api = V2 + "/" + id;
        String detail = "/v2/holdings/" + id;
        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"BUY","trade_date":"2026-11-16","shares":100,"unit_price":20}
                """)).andExpect(status().isCreated());
        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"SELL","trade_date":"2026-11-16","shares":100,"unit_price":30}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.realized_pnl").value(1451));
        String deletePage = tradeDeletePage(id, "SELL");

        mvc.perform(get(detail)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + deletePage + "\">刪除")));
        mvc.perform(get(deletePage)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<dd>賣出</dd>")))
                .andExpect(content().string(containsString("<dd>2026-11-16</dd>")))
                .andExpect(content().string(containsString("<dd>100</dd>")))
                .andExpect(content().string(containsString("<dd>30.00</dd>")))
                .andExpect(content().string(containsString("href=\"" + detail + "\">取消")))
                .andExpect(content().string(containsString("action=\"" + deletePage + "\"")));
        mvc.perform(get(detail)).andExpect(status().isOk());
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$.length()").value(3));

        mvc.perform(post(deletePage).param("lastTrade", "false"))
                .andExpect(redirectedUrl(detail))
                .andExpect(flash().attribute("notice", "已刪除交易紀錄"));
        mvc.perform(get(api)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shares").value(200))
                .andExpect(jsonPath("$.remaining_cost").value(3040))
                .andExpect(jsonPath("$.average_cost").value(15.2))
                .andExpect(jsonPath("$.realized_pnl").value(0))
                .andExpect(jsonPath("$.unrealized_pnl").value(20854));
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].unit_price").value(10))
                .andExpect(jsonPath("$[1].unit_price").value(20));
        mvc.perform(post(deletePage).param("lastTrade", "false")).andExpect(status().isNotFound());
    }

    @Test
    void deletingOpeningOrBuyRollsBackWhenThereWouldBeAHistoricalOversale() throws Exception {
        long id = createLegacy(OLD_BUY);
        String api = V2 + "/" + id;
        String detail = "/v2/holdings/" + id;
        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content(NEW_BUY))
                .andExpect(status().isCreated());
        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"SELL","trade_date":"2026-11-17","shares":2100,"unit_price":170}
                """)).andExpect(status().isCreated());
        String openingDelete = tradeDeletePage(id, "OPENING");
        String buyDelete = tradeDeletePage(id, "BUY");
        mvc.perform(get(openingDelete)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<dd>期初持股</dd>")))
                .andExpect(content().string(containsString("由 1.0 帶入，無交易日期")))
                .andExpect(content().string(containsString("<dd>2000</dd>")))
                .andExpect(content().string(containsString("<dd>150.00</dd>")));
        mvc.perform(get(buyDelete)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<dd>買進</dd>")));

        var rejected = mvc.perform(post(openingDelete).param("lastTrade", "false"))
                .andExpect(redirectedUrl(detail))
                .andExpect(flash().attribute("deleteError", containsString("2026-11-17 賣出 2100 股，當時僅持有 300 股")))
                .andReturn();
        mvc.perform(get(detail).flashAttrs(rejected.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("刪除後會發生超賣")));
        mvc.perform(post(buyDelete).param("lastTrade", "false"))
                .andExpect(redirectedUrl(detail))
                .andExpect(flash().attribute("deleteError", containsString("當時僅持有 2000 股")));
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].side").value("OPENING"))
                .andExpect(jsonPath("$[1].side").value("BUY"))
                .andExpect(jsonPath("$[2].side").value("SELL"));

        mvc.perform(post(tradeDeletePage(id, "SELL")).param("lastTrade", "false")).andExpect(redirectedUrl(detail));
        mvc.perform(post(openingDelete).param("lastTrade", "false")).andExpect(redirectedUrl(detail));
        mvc.perform(get(api)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shares").value(300))
                .andExpect(jsonPath("$.remaining_cost").value(48068))
                .andExpect(jsonPath("$.v2_managed").value(true));
    }

    @Test
    void deletingTheLastOpeningTradeRequiresExplicitWholeHoldingConfirmation() throws Exception {
        long id = createLegacy(OLD_BUY);
        String deletePage = tradeDeletePage(id, "OPENING");
        mvc.perform(get(deletePage)).andExpect(status().isOk())
                .andExpect(content().string(containsString("這是最後一筆交易")))
                .andExpect(content().string(containsString("移除所有交易和已實現損益紀錄")))
                .andExpect(content().string(containsString("App 1.0 重新整理後")))
                .andExpect(content().string(containsString("name=\"lastTrade\" value=\"true\"")));
        mvc.perform(post(deletePage)).andExpect(status().isBadRequest());
        mvc.perform(post(deletePage).param("lastTrade", "false")).andExpect(redirectedUrl(deletePage))
                .andExpect(flash().attribute("deleteError", containsString("重新確認刪除範圍")));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isOk());

        mvc.perform(post(deletePage).param("lastTrade", "true")).andExpect(redirectedUrl("/v2/holdings"));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(V2 + "/" + id)).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM holding_trades WHERE holding_id = ?", Long.class, id));
    }

    @Test
    void staleLastTradeConfirmationAndForeignTradeIdCannotRemoveRecords() throws Exception {
        long id = createLegacy(OLD_BUY);
        long other = createLegacy(OLD_BUY.replace("0050", "2330"));
        String api = V2 + "/" + id;
        String deletePage = tradeDeletePage(id, "OPENING");
        String foreignTrade = tradeDeletePage(other, "OPENING")
                .replace("/holdings/" + other + "/", "/holdings/" + id + "/");
        mvc.perform(get(foreignTrade)).andExpect(status().isNotFound());
        mvc.perform(post(foreignTrade).param("lastTrade", "true")).andExpect(status().isNotFound());
        mvc.perform(get(deletePage)).andExpect(status().isOk())
                .andExpect(content().string(containsString("這是最後一筆交易")));

        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content(NEW_BUY))
                .andExpect(status().isCreated());
        mvc.perform(post(deletePage).param("lastTrade", "true")).andExpect(redirectedUrl(deletePage))
                .andExpect(flash().attribute("deleteError", containsString("重新確認刪除範圍")));
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(V2 + "/" + other + "/trades")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(deletePage)).andExpect(status().isOk())
                .andExpect(content().string(containsString("確認刪除此筆交易")));
    }

    @Test
    void editKeepsSameDayOrderAndMovesDatedTradesToTheEndOfTheirNewDay() throws Exception {
        var created = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"2330","trade_date":"2026-11-15","shares":200,"unit_price":10}
                """)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();
        String api = V2 + "/" + id;
        String detail = "/v2/holdings/" + id;
        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"BUY","trade_date":"2026-11-15","shares":100,"unit_price":20}
                """)).andExpect(status().isCreated());
        mvc.perform(post(api + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"SELL","trade_date":"2026-11-16","shares":100,"unit_price":30}
                """)).andExpect(status().isCreated());

        var buyIds = jdbc.queryForList(
                "SELECT id FROM holding_trades WHERE holding_id = ? AND side = 'BUY' ORDER BY id", Long.class, id);
        String first = detail + "/trades/" + buyIds.get(0) + "/edit";
        String second = detail + "/trades/" + buyIds.get(1) + "/edit";
        mvc.perform(get(detail)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + first + "\">修改")));
        mvc.perform(get(first)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"tradeDate\" value=\"2026-11-15\"")))
                .andExpect(content().string(containsString("name=\"shares\" value=\"200\"")))
                .andExpect(content().string(containsString("name=\"unitPrice\" value=\"10.00\"")));
        mvc.perform(post(first).param("tradeDate", "2026-11-15").param("shares", "200").param("unitPrice", "12"))
                .andExpect(redirectedUrl(detail)).andExpect(flash().attribute("notice", "已修改交易紀錄"));
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$[0].unit_price").value(12))
                .andExpect(jsonPath("$[1].unit_price").value(20));

        mvc.perform(post(second).param("tradeDate", "2026-11-16").param("shares", "100")
                        .param("unitPrice", "20").param("side", "SELL"))
                .andExpect(redirectedUrl(detail));
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$[1].side").value("SELL"))
                .andExpect(jsonPath("$[2].side").value("BUY"))
                .andExpect(jsonPath("$[2].trade_date").value("2026-11-16"));
        mvc.perform(get(api)).andExpect(jsonPath("$.shares").value(200))
                .andExpect(jsonPath("$.remaining_cost").value(3230))
                .andExpect(jsonPath("$.average_cost").value(16.15))
                .andExpect(jsonPath("$.realized_pnl").value(1761))
                .andExpect(jsonPath("$.unrealized_pnl").value(20664));

        mvc.perform(post(first).param("tradeDate", "2026-11-15").param("shares", "99").param("unitPrice", "12"))
                .andExpect(status().isOk()).andExpect(content().string(containsString(
                        "2026-11-16 賣出 100 股，當時僅持有 99 股")));
        String tomorrow = LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).plusDays(1).toString();
        mvc.perform(post(first).param("tradeDate", tomorrow).param("shares", "200").param("unitPrice", "12"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("交易日期不能晚於今天")));
        mvc.perform(post(first).param("shares", "200").param("unitPrice", "12"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("請填寫交易日期")));
        mvc.perform(get(api + "/trades")).andExpect(jsonPath("$[0].shares").value(200))
                .andExpect(jsonPath("$[0].trade_date").value("2026-11-15"));
    }

    @Test
    void editingLegacyOpeningOnlyConvertsAfterConfirmationAndSuccessfulValidation() throws Exception {
        long id = createLegacy(OLD_BUY);
        long openingId = jdbc.queryForObject("SELECT id FROM holding_trades WHERE holding_id = ?", Long.class, id);
        String detail = "/v2/holdings/" + id;
        String edit = detail + "/trades/" + openingId + "/edit";
        mvc.perform(get(edit)).andExpect(status().isOk())
                .andExpect(content().string(containsString("由 1.0 帶入，無交易日期")))
                .andExpect(content().string(containsString("我同意將這檔持股交由 2.0 管理")))
                .andExpect(content().string(not(containsString("name=\"tradeDate\""))));
        mvc.perform(post(edit).param("shares", "300").param("unitPrice", "140"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("請先確認由 2.0 管理")));
        mvc.perform(post(edit).param("shares", "0").param("unitPrice", "140").param("confirmV2", "true"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("股數必須大於 0")));
        mvc.perform(post(edit).param("shares", "300").param("unitPrice", "140.001").param("confirmV2", "true"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("每股價格最多兩位小數")));
        mvc.perform(post(edit).param("tradeDate", "2026-11-16").param("shares", "300")
                        .param("unitPrice", "140").param("confirmV2", "true"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("期初持股無交易日期")));
        mvc.perform(get(V1 + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lots").value(2)).andExpect(jsonPath("$.buy_price").value(150));
        mvc.perform(get(V2 + "/" + id)).andExpect(jsonPath("$.v2_managed").value(false));

        mvc.perform(post(edit).param("shares", "300").param("unitPrice", "140").param("confirmV2", "true"))
                .andExpect(redirectedUrl(detail));
        mvc.perform(get(V1)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/summary")).andExpect(jsonPath("$.total_cost").value(0));
        mvc.perform(get(V2 + "/" + id)).andExpect(jsonPath("$.v2_managed").value(true));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(jsonPath("$[0].side").value("OPENING"))
                .andExpect(jsonPath("$[0].trade_date").value(nullValue()))
                .andExpect(jsonPath("$[0].shares").value(300))
                .andExpect(jsonPath("$[0].unit_price").value(140));

        mvc.perform(get(edit)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("我同意將這檔持股交由 2.0 管理"))));
        mvc.perform(post(edit).param("shares", "450").param("unitPrice", "141")).andExpect(redirectedUrl(detail));
        mvc.perform(get(V2 + "/" + id)).andExpect(jsonPath("$.shares").value(450));
    }

    @Test
    void delistedHoldingsKeepV1FormatAndShowHistoricPriceOnlyInV2() throws Exception {
        long legacy = createLegacy(OLD_BUY);
        long converted = createLegacy(OLD_BUY.replace("0050", "2330"));
        String convertedPath = V2 + "/" + converted;
        mvc.perform(post(convertedPath + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"SELL","trade_date":"2026-11-15","shares":100,"unit_price":160,"confirm_v2":true}
                """)).andExpect(status().isCreated());
        recordMarketEvent("0050", "TWSE", "2026-11-16", "DELISTED", "元大台灣50");
        recordMarketEvent("2330", "TWSE", "2026-11-16", "DELISTED", "台積電");
        jdbc.update("""
                INSERT INTO market_final_close VALUES
                ('0050', 'TWSE', DATE '2026-11-16', DATE '2026-10-01', DATE '2026-11-12', 123.45)
                """);

        mvc.perform(get(V1 + "/" + legacy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("TWSE"))
                .andExpect(jsonPath("$.name").value("元大台灣50"))
                .andExpect(jsonPath("$.current_price").value(nullValue()))
                .andExpect(jsonPath("$.price_date").value(nullValue()))
                .andExpect(jsonPath("$.pnl").value(nullValue()))
                .andExpect(jsonPath("$.return_pct").value(nullValue()));
        mvc.perform(get("/api/v1/summary")).andExpect(jsonPath("$.total_cost").value(300427))
                .andExpect(jsonPath("$.market_value").value(0)).andExpect(jsonPath("$.quote_missing").value(1));
        mvc.perform(get("/holdings")).andExpect(content().string(containsString("已下市")))
                .andExpect(content().string(not(containsString("123.45"))));
        mvc.perform(get("/v2/holdings")).andExpect(content().string(containsString("最後交易日收盤價 123.45")))
                .andExpect(content().string(containsString("2026-11-12")))
                .andExpect(content().string(containsString("已下市／下櫃、無法估值的持股成本")))
                .andExpect(content().string(containsString(">無法估值<")));
        mvc.perform(get(convertedPath)).andExpect(jsonPath("$.realized_pnl").value(908.65))
                .andExpect(jsonPath("$.unrealized_pnl").value(nullValue()));
        mvc.perform(post(V2 + "/" + legacy + "/trades").contentType(MediaType.APPLICATION_JSON).content("""
                {"side":"BUY","trade_date":"2026-11-15","shares":100,"unit_price":100,"confirm_v2":true}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("最後交易日為 2026-11-12")));
        mvc.perform(post(V1).contentType(MediaType.APPLICATION_JSON).content(OLD_BUY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message", containsString("已下市")));
    }

    @Test
    void buyValidationUsesEffectiveDatesAcrossHaltsDelistingAndRelisting() throws Exception {
        recordMarketEvent("2330", "TWSE", "2026-11-14", "HALTED", "台積電");
        recordMarketEvent("2330", "TWSE", "2026-11-16", "DELISTED", "台積電");
        String create = """
                {"code":"2330","trade_date":"%s","shares":300,"unit_price":100}
                """;
        mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(create.formatted("2026-11-16")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message", containsString("下市")));
        var result = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON)
                .content(create.formatted("2026-11-13"))).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsByteArray()).get("id").longValue();
        String trades = V2 + "/" + id + "/trades";
        String buy = """
                {"side":"BUY","trade_date":"%s","shares":100,"unit_price":100}
                """;
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(buy.formatted("2026-11-14")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message", containsString("停止買賣")));
        recordMarketEvent("2330", "TWSE", "2026-11-17", "RELISTED", "台積電");
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(buy.formatted("2026-11-17")))
                .andExpect(status().isCreated());
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(buy.formatted("2026-11-16")))
                .andExpect(status().isBadRequest());
        long first = jdbc.queryForObject("SELECT MIN(id) FROM holding_trades WHERE holding_id = ?", Long.class, id);
        mvc.perform(post("/v2/holdings/" + id + "/trades/" + first + "/edit")
                        .param("tradeDate", "2026-11-16").param("shares", "300").param("unitPrice", "100"))
                .andExpect(content().string(containsString("無法登記該日或之後的買進")));
        mvc.perform(get(trades)).andExpect(jsonPath("$[0].trade_date").value("2026-11-13"));
    }

    @Test
    void newTradesUseTheInclusiveRollingFiveYearBoundary() throws Exception {
        LocalDate floor = LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).minusYears(5);
        String buy = """
                {"code":"2330","trade_date":"%s","shares":300,"unit_price":100}
                """;
        mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(buy.formatted(floor.minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString(floor + "（五年前）")));
        var created = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(buy.formatted(floor)))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();
        String trades = V2 + "/" + id + "/trades";
        String sale = """
                {"side":"SELL","trade_date":"%s","shares":100,"unit_price":110}
                """;
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(sale.formatted(floor.minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString(floor.toString())));
        mvc.perform(post(trades).contentType(MediaType.APPLICATION_JSON).content(sale.formatted(floor)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shares").value(200));
        mvc.perform(get("/v2/holdings/" + id))
                .andExpect(content().string(containsString("min=\"" + floor + "\"")));
        mvc.perform(get("/v2/holdings/trade-date-range").param("code", "2330"))
                .andExpect(jsonPath("$.minDate").value(floor.toString()))
                .andExpect(jsonPath("$.firstListingDate").value(nullValue()));
    }

    @Test
    void firstListingDateLimitsCreationAndEditingButOldTradesRemainEditable() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        LocalDate listing = today.minusYears(1);
        jdbc.update("INSERT INTO market_identity (code, issuer_id, listed_on) VALUES ('2330', '12345678', ?)", listing);
        String buy = """
                {"code":"2330","trade_date":"%s","shares":300,"unit_price":100}
                """;
        mvc.perform(get("/v2/holdings/trade-date-range").param("code", "2330"))
                .andExpect(jsonPath("$.minDate").value(listing.toString()))
                .andExpect(jsonPath("$.firstListingDate").value(listing.toString()));
        mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(buy.formatted(listing.minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("不得早於首次上市或上櫃日")));
        var created = mvc.perform(post(V2).contentType(MediaType.APPLICATION_JSON).content(buy.formatted(listing)))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();
        long tradeId = jdbc.queryForObject("SELECT id FROM holding_trades WHERE holding_id = ?", Long.class, id);
        String edit = "/v2/holdings/" + id + "/trades/" + tradeId + "/edit";
        mvc.perform(get(edit)).andExpect(content().string(containsString("min=\"" + listing + "\"")));
        mvc.perform(post(edit).param("tradeDate", listing.minusDays(1).toString()).param("shares", "300")
                        .param("unitPrice", "101"))
                .andExpect(content().string(containsString("交易日期不得早於首次上市或上櫃日 " + listing)));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(jsonPath("$[0].unit_price").value(100));
        mvc.perform(post(edit).param("tradeDate", listing.toString()).param("shares", "300").param("unitPrice", "101"))
                .andExpect(redirectedUrl("/v2/holdings/" + id));

        LocalDate fiveYearsAgo = today.minusYears(5);
        LocalDate oldTrade = fiveYearsAgo.minusDays(1);
        jdbc.update("UPDATE market_identity SET listed_on = ? WHERE code = '2330'", oldTrade.minusYears(1));
        jdbc.update("UPDATE holding_trades SET trade_date = ? WHERE id = ?", fiveYearsAgo, tradeId);
        mvc.perform(get(edit)).andExpect(content().string(containsString("type=\"date\"")))
                .andExpect(content().string(containsString("min=\"" + fiveYearsAgo + "\"")))
                .andExpect(content().string(not(containsString("超過五年，日期不可修改"))));
        mvc.perform(post(edit).param("tradeDate", oldTrade.toString())
                        .param("shares", "999").param("unitPrice", "999"))
                .andExpect(content().string(containsString("修改交易日期最早可填寫 " + fiveYearsAgo)));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(jsonPath("$[0].shares").value(300))
                .andExpect(jsonPath("$[0].trade_date").value(fiveYearsAgo.toString()));
        mvc.perform(post(edit).param("tradeDate", fiveYearsAgo.plusDays(1).toString())
                        .param("shares", "300").param("unitPrice", "101"))
                .andExpect(redirectedUrl("/v2/holdings/" + id));

        jdbc.update("UPDATE holding_trades SET trade_date = ? WHERE id = ?", oldTrade, tradeId);
        mvc.perform(get(edit)).andExpect(content().string(not(containsString("type=\"date\""))))
                .andExpect(content().string(containsString("超過五年，日期不可修改")))
                .andExpect(content().string(containsString("name=\"tradeDate\" value=\"" + oldTrade + "\"")));
        mvc.perform(post(edit).param("tradeDate", today.toString()).param("shares", "999").param("unitPrice", "999"))
                .andExpect(content().string(containsString("超過五年的交易不可修改日期")))
                .andExpect(content().string(containsString("name=\"tradeDate\" value=\"" + oldTrade + "\"")));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(jsonPath("$[0].shares").value(300))
                .andExpect(jsonPath("$[0].trade_date").value(oldTrade.toString()));
        mvc.perform(post(edit).param("tradeDate", oldTrade.toString()).param("shares", "301").param("unitPrice", "102"))
                .andExpect(redirectedUrl("/v2/holdings/" + id));
        mvc.perform(get(V2 + "/" + id + "/trades")).andExpect(jsonPath("$[0].shares").value(301))
                .andExpect(jsonPath("$[0].trade_date").value(oldTrade.toString()));
    }

    @Test
    void marketTransferDateAloneIsNotTreatedAsFirstListingDate() throws Exception {
        LocalDate transfer = LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).minusYears(1);
        recordMarketEvent("2330", "TWSE", transfer.toString(), "RELISTED", "台積電");
        jdbc.update("INSERT INTO market_identity (code, issuer_id, listed_on) VALUES ('2330', '12345678', ?)", transfer);
        mvc.perform(get("/v2/holdings/trade-date-range").param("code", "2330"))
                .andExpect(jsonPath("$.firstListingDate").value(nullValue()));
        var earlierListing = transfer.minusYears(1);
        jdbc.update("UPDATE market_identity SET listed_on = ? WHERE code = '2330'", earlierListing);
        mvc.perform(get("/v2/holdings/trade-date-range").param("code", "2330"))
                .andExpect(jsonPath("$.firstListingDate").value(earlierListing.toString()));
    }

    private void recordMarketEvent(String code, String market, String date, String kind, String name) {
        jdbc.update("""
                INSERT INTO market_event (code, market, effective_date, kind, stock_name) VALUES (?, ?, ?, ?, ?)
                """, code, market, LocalDate.parse(date), kind, name);
    }

    private String tradeDeletePage(long holdingId, String side) {
        long tradeId = jdbc.queryForObject("SELECT id FROM holding_trades WHERE holding_id = ? AND side = ?",
                Long.class, holdingId, side);
        return "/v2/holdings/" + holdingId + "/trades/" + tradeId + "/delete";
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
