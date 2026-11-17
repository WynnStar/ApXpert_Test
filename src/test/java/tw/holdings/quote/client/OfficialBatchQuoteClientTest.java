package tw.holdings.quote.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tw.holdings.quote.Market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficialBatchQuoteClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LocalDate today = LocalDate.of(2026, 11, 18);

    @Test
    void parsesTwseAndTpexOfficialFieldsWithoutInventingTodaysDate() throws Exception {
        var twse = OfficialBatchQuoteClient.parse(json.readTree("""
                [{"Date":"1151116","Code":"2330","Name":"台積電",
                  "TradeVolume":"1,000","ClosingPrice":"2,215.00"}]
                """), Market.TWSE, today);
        var tpex = OfficialBatchQuoteClient.parse(json.readTree("""
                [{"Date":"1151117","SecuritiesCompanyCode":"6488","CompanyName":"環球晶",
                  "TradingShares":"10450392","Close":"546.00"}]
                """), Market.TPEX, today);

        assertEquals(LocalDate.of(2026, 11, 16), twse.priceDate());
        assertEquals(twse.priceDate(), twse.quotes().get("2330").priceDate());
        assertEquals(new BigDecimal("2215.00"), twse.quotes().get("2330").close());
        assertEquals(Market.TWSE, twse.quotes().get("2330").market());
        assertEquals(LocalDate.of(2026, 11, 17), tpex.priceDate());
        assertEquals(new BigDecimal("546.00"), tpex.quotes().get("6488").close());
        assertEquals(Market.TPEX, tpex.quotes().get("6488").market());
    }

    @Test
    void keepsIdentityWithoutTreatingZeroVolumeOrMissingPriceAsATrade() throws Exception {
        var snapshot = OfficialBatchQuoteClient.parse(json.readTree("""
                [{"Date":"1151117","Code":"2330","Name":"台積電","TradeVolume":"0","ClosingPrice":"100.00"},
                 {"Date":"1151117","Code":"0050","Name":"元大台灣50","TradeVolume":"10","ClosingPrice":"--"}]
                """), Market.TWSE, today);

        assertEquals(2, snapshot.quotes().size());
        assertEquals("元大台灣50", snapshot.quotes().get("0050").name());
        assertNull(snapshot.quotes().get("2330").close());
        assertNull(snapshot.quotes().get("2330").priceDate());
        assertNull(snapshot.quotes().get("0050").close());
    }

    @Test
    void ignoresFutureRowsAndUsesLatestUsableDuplicate() throws Exception {
        var snapshot = OfficialBatchQuoteClient.parse(json.readTree("""
                [{"Date":"20261117","Code":"2330","Name":"台積電","TradeVolume":"10","ClosingPrice":"100.00"},
                 {"Date":"20261116","Code":"2330","Name":"台積電","TradeVolume":"10","ClosingPrice":"90.00"},
                 {"Date":"20261119","Code":"0050","Name":"元大台灣50","TradeVolume":"10","ClosingPrice":"200.00"}]
                """), Market.TWSE, today);

        assertEquals(1, snapshot.quotes().size());
        assertEquals(new BigDecimal("100.00"), snapshot.quotes().get("2330").close());
    }

    @Test
    void rejectsEmptyMalformedAndInvalidBatchDocuments() {
        for (String document : new String[] { "[]", "{}", """
                [{"Date":"bad-date","Code":"2330","Name":"台積電","TradeVolume":"10","ClosingPrice":"100"}]
                """, """
                [{"Date":"1151117","Code":"2330","Name":"台積電","TradeVolume":"10","ClosingPrice":"invalid"}]
                """ }) {
            assertThrows(IOException.class,
                    () -> OfficialBatchQuoteClient.parse(json.readTree(document), Market.TWSE, today));
        }
    }
}
