package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialMarketClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesOfficialDelistingAndHaltDatesFromBothMarkets() throws Exception {
        var http = mock(OfficialQuoteHttp.class);
        when(http.fetch(eq(Market.TWSE), contains("/company/suspendListing"))).thenReturn(json.readTree("""
                {"status":"ok","data":[["114/07/24","新光金","2888"]]}
                """));
        when(http.fetch(eq(Market.TPEX), contains("/company/deListed"))).thenReturn(json.readTree("""
                {"stat":"ok","tables":[{"data":[["6747","亨泰光","114-12-04","終止上櫃",""]]}]}
                """));
        when(http.fetch(eq(Market.TPEX), contains("reason=2"))).thenReturn(json.readTree("""
                {"stat":"ok","tables":[{"data":[["1597","直得","109-12-23","轉上市",""]]}]}
                """));
        when(http.fetchDocument(eq(Market.TWSE), contains("/exchangeReport/TWTAWU"))).thenReturn(json.readTree("""
                [{"Code":"2314","Name":"台揚","TradingHaltDate":"1151110","TradingHaltTime":"080000",
                  "TradingResumptionDate":"1151111"}]
                """));
        when(http.fetchDocument(eq(Market.TPEX), contains("/tpex_spendi_history"))).thenReturn(json.readTree("""
                [{"SecuritiesCompanyCode":"1788","CompanyName":"杏昌","DateOfSuspendedTrading":"1150618",
                  "TimeOfSuspendedTrading":"120000","DateOfResumedTrading":""},
                 {"SecuritiesCompanyCode":"1788","CompanyName":"杏昌","DateOfResumedTrading":"1150622"}]
                """));
        var official = new OfficialMarketClient(http);

        assertEquals(LocalDate.of(2025, 7, 24), official.delistings(Market.TWSE, 2025).get(0).date());
        assertEquals(LocalDate.of(2025, 12, 4), official.delistings(Market.TPEX, 2025).get(0).date());
        assertEquals(new MarketEvent("1597", Market.TWSE, LocalDate.of(2020, 12, 23),
                MarketEvent.Kind.RELISTED, "直得"), official.transfersToTwse(2020).get(0));
        assertEquals(LocalDate.of(2026, 11, 10), official.halts(Market.TWSE).get(0).date());
        assertEquals(MarketEvent.Kind.RESUMED, official.halts(Market.TWSE).get(1).kind());
        assertEquals(LocalDate.of(2026, 6, 19), official.halts(Market.TPEX).get(0).date());
        assertEquals(LocalDate.of(2026, 6, 22), official.halts(Market.TPEX).get(1).date());
    }

    @Test
    void closingPriceUsesLastActualTradeBeforeTheCutoff() throws Exception {
        var fields = json.readTree("[\"日 期\",\"成交張數\",\"收盤\"]");
        var rows = json.readTree("""
                [["114/07/11","1,234","11.80"],["114/07/14","0","11.80"],
                 ["114/07/15","--","--"],["114/07/24","1,000","30.00"]]
                """);
        var price = OfficialQuoteData.latest(fields, rows, LocalDate.of(2025, 7, 23));
        assertEquals(LocalDate.of(2025, 7, 11), price.date());
        assertEquals(new BigDecimal("11.80"), price.close());
    }
}
