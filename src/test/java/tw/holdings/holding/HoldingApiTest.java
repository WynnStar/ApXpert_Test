package tw.holdings.holding;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tw.holdings.quote.Market;
import tw.holdings.quote.QuoteResult;
import tw.holdings.quote.QuoteService;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:app-v1-api-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class HoldingApiTest {
    private static final String HOLDINGS = "/api/v1/holdings";
    private static final String BUY = """
            {"code":"0050","lots":2,"buy_price":150.00}
            """;

    @Autowired MockMvc mvc;
    @Autowired HoldingRepository repository;
    @Autowired ObjectMapper mapper;
    @MockitoBean QuoteService quotes;

    @BeforeEach
    void clearHoldings() {
        repository.deleteAll();
    }

    @Test
    void emptyListAndSummaryHaveAppShapes() throws Exception {
        mvc.perform(get(HOLDINGS)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/summary")).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total_cost":0,"market_value":0,"pnl":0,"return_pct":0.00,"quote_missing":0}
                        """));
        verifyNoInteractions(quotes);
    }

    @Test
    void createsReadsUpdatesAndDeletesWithAppStatusCodes() throws Exception {
        when(quotes.lookup(eq("0050"), nullable(Market.class))).thenReturn(new QuoteResult(
                QuoteResult.Status.FOUND, "元大台灣50", Market.TWSE,
                new BigDecimal("145.50"), LocalDate.of(2026, 11, 12)));

        var result = mvc.perform(post(HOLDINGS).contentType(MediaType.APPLICATION_JSON).content(BUY))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0050"))
                .andExpect(jsonPath("$.name").value("元大台灣50"))
                .andExpect(jsonPath("$.market").value("TWSE"))
                .andExpect(jsonPath("$.lots").value(2))
                .andExpect(jsonPath("$.buy_price").value(150.00))
                .andExpect(jsonPath("$.current_price").value(145.50))
                .andExpect(jsonPath("$.price_date").value("2026-11-12"))
                .andExpect(jsonPath("$.pnl").value(-10132))
                .andExpect(jsonPath("$.return_pct").value(-3.37))
                .andReturn();
        var created = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertTrue(created.get("id").isIntegralNumber());
        assertTrue(created.get("pnl").isIntegralNumber());
        assertTrue(created.get("code").isTextual());
        assertTrue(created.get("buy_price").isNumber());
        String item = HOLDINGS + "/" + created.get("id").longValue();

        mvc.perform(post(HOLDINGS).contentType(MediaType.APPLICATION_JSON).content(BUY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DUPLICATE_CODE"));
        mvc.perform(get(HOLDINGS)).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("0050"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(item)).andExpect(status().isOk()).andExpect(jsonPath("$.lots").value(2));
        mvc.perform(get("/api/v1/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_cost").value(300427))
                .andExpect(jsonPath("$.market_value").value(291000))
                .andExpect(jsonPath("$.return_pct").value(-3.37))
                .andExpect(jsonPath("$.quote_missing").value(0));

        mvc.perform(put(item).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"2330\",\"lots\":3,\"buy_price\":151.50}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(put(item).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lots\":3,\"buy_price\":151.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(created.get("id").intValue()))
                .andExpect(jsonPath("$.code").value("0050"))
                .andExpect(jsonPath("$.lots").value(3)).andExpect(jsonPath("$.buy_price").value(151.50));
        mvc.perform(delete(item)).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get(item)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(put(item).contentType(MediaType.APPLICATION_JSON).content("{\"lots\":1,\"buy_price\":10}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(delete(item)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void returnsExplicitNullsForMissingQuoteButKeepsHoldingIdentity() throws Exception {
        repository.saveAndFlush(new Holding("6488", 1, new BigDecimal("100.00"), "環球晶", Market.TPEX));
        when(quotes.lookup("6488", Market.TPEX)).thenReturn(
                new QuoteResult(QuoteResult.Status.UNAVAILABLE, null, null, null, null));

        mvc.perform(get(HOLDINGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("環球晶"))
                .andExpect(jsonPath("$[0].market").value("TPEX"))
                .andExpect(jsonPath("$[0].current_price").hasJsonPath())
                .andExpect(jsonPath("$[0].current_price").value(nullValue()))
                .andExpect(jsonPath("$[0].price_date").hasJsonPath())
                .andExpect(jsonPath("$[0].price_date").value(nullValue()))
                .andExpect(jsonPath("$[0].pnl").hasJsonPath())
                .andExpect(jsonPath("$[0].pnl").value(nullValue()))
                .andExpect(jsonPath("$[0].return_pct").hasJsonPath())
                .andExpect(jsonPath("$[0].return_pct").value(nullValue()));
        mvc.perform(get("/api/v1/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_cost").value(100142))
                .andExpect(jsonPath("$.market_value").value(0))
                .andExpect(jsonPath("$.pnl").value(0))
                .andExpect(jsonPath("$.quote_missing").value(1));
    }

    @Test
    void distinguishesUnknownStockFromUnavailableSourceAndDoesNotSaveEither() throws Exception {
        when(quotes.lookup("0000", null)).thenReturn(
                new QuoteResult(QuoteResult.Status.NOT_FOUND, null, null, null, null));
        when(quotes.lookup("1111", null)).thenReturn(
                new QuoteResult(QuoteResult.Status.UNAVAILABLE, null, null, null, null));
        mvc.perform(post(HOLDINGS).contentType(MediaType.APPLICATION_JSON).content(BUY.replace("0050", "0000")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").isString());
        mvc.perform(post(HOLDINGS).contentType(MediaType.APPLICATION_JSON).content(BUY.replace("0050", "1111")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("QUOTE_UNAVAILABLE"));
        mvc.perform(get(HOLDINGS)).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"code\":2330,\"lots\":1,\"buy_price\":10}",
        "{\"code\":\"2330\",\"lots\":1.5,\"buy_price\":10}",
        "{\"code\":\"2330\",\"lots\":\"1\",\"buy_price\":10}",
        "{\"code\":\"2330\",\"lots\":1000,\"buy_price\":10}",
        "{\"code\":\"2330\",\"lots\":1,\"buy_price\":\"10\"}",
        "{\"code\":\"2330\",\"lots\":1,\"buy_price\":10.555}",
        "{\"code\":\"2330\",\"lots\":1,\"buy_price\":0}",
        "{"
    })
    void rejectsInvalidJsonFieldsBeforeCallingOfficialQuotes(String json) throws Exception {
        mvc.perform(post(HOLDINGS).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").isString());
        verifyNoInteractions(quotes);
    }
}
