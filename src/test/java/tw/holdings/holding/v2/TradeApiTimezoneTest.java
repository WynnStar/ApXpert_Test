package tw.holdings.holding.v2;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:utc-trade-api-test;DB_CLOSE_DELAY=-1",
        "quotes.market-status-sync=false", "quotes.warmup-enabled=false", "quotes.calendar-sync-enabled=false"
})
@AutoConfigureMockMvc
class TradeApiTimezoneTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoBean QuoteService quotes;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM holding_trades");
        jdbc.update("DELETE FROM holdings");
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Taipei"));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-17T16:00:00Z"));
        var quote = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("110.00"),
                LocalDate.parse("2026-11-17"));
        when(quotes.lookup(anyString(), any())).thenReturn(quote);
        when(quotes.forDisplay(anyString(), any())).thenReturn(quote);
    }

    @Test
    void taipeiTodayCanBeCreatedAndStoredWithoutShiftingTheTradeOrQuoteDate() throws Exception {
        mvc.perform(post("/api/v2/holdings").contentType(MediaType.APPLICATION_JSON)
                .content(buy("2026-11-18")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price_date").value("2026-11-17"));
        long id = jdbc.queryForObject("SELECT id FROM holdings WHERE code = '2330'", Long.class);
        mvc.perform(get("/api/v2/holdings/" + id + "/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trade_date").value("2026-11-18"));
        assertEquals(LocalDate.parse("2026-11-18"),
                jdbc.queryForObject("SELECT trade_date FROM holding_trades WHERE holding_id = ?",
                        (row, index) -> row.getObject("trade_date", LocalDate.class), id));
    }

    @Test
    void futureTaipeiDateIsRejectedWithoutSavingAnyHoldingOrTrade() throws Exception {
        mvc.perform(post("/api/v2/holdings").contentType(MediaType.APPLICATION_JSON)
                .content(buy("2026-11-19")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM holdings", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM holding_trades", Long.class));
    }

    @Test
    void dateRangeAndValidationAdvanceTogetherAtTaipeiMidnight() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-17T15:59:59Z"));
        mvc.perform(get("/v2/holdings/trade-date-range").param("code", "2330"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDate").value("2026-11-17"));
        mvc.perform(post("/api/v2/holdings").contentType(MediaType.APPLICATION_JSON)
                .content(buy("2026-11-18"))).andExpect(status().isBadRequest());

        when(clock.instant()).thenReturn(Instant.parse("2026-11-17T16:00:00Z"));
        mvc.perform(get("/v2/holdings/trade-date-range").param("code", "2330"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDate").value("2026-11-18"));
        mvc.perform(post("/api/v2/holdings").contentType(MediaType.APPLICATION_JSON)
                .content(buy("2026-11-18"))).andExpect(status().isCreated());
    }

    private static String buy(String date) {
        return """
                {"code":"2330","trade_date":"%s","shares":300,"unit_price":100}
                """.formatted(date);
    }
}
