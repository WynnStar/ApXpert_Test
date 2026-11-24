package tw.holdings.quote.client;
import java.io.IOException;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tw.holdings.quote.Market;
import static org.junit.jupiter.api.Assertions.*;

class HolidayCalendarParserTest {
    private final ObjectMapper json=new ObjectMapper();
    ObjectNode fixture(Market market) throws Exception {
        try(var input=getClass().getResourceAsStream("/quotes/calendar-"+(market==Market.TWSE?"twse":"tpex")+"-2026.json")) {
            assertNotNull(input);return (ObjectNode)json.readTree(input);
        }
    }
    @ParameterizedTest @EnumSource(Market.class)
    void readsRealOfficialAnnualCalendarIncludingOpenAndSettlementDays(Market market) throws Exception {
        var days=HolidayCalendarParser.parse(fixture(market),market,2026);
        assertFalse(days.get(LocalDate.of(2026,1,1)).open());
        assertTrue(days.get(LocalDate.of(2026,1,2)).open());
        assertTrue(days.get(LocalDate.of(2026,2,11)).open());
        assertFalse(days.get(LocalDate.of(2026,2,12)).open());
        assertFalse(days.get(LocalDate.of(2026,2,13)).open());
        assertFalse(days.get(LocalDate.of(2026,2,16)).open());
        assertFalse(days.get(LocalDate.of(2026,2,20)).open());
        assertTrue(days.get(LocalDate.of(2026,2,23)).open());
        assertFalse(days.get(LocalDate.of(2026,4,6)).open());
        assertFalse(days.get(LocalDate.of(2026,10,26)).open());
    }
    @ParameterizedTest @EnumSource(Market.class)
    void rejectsAnotherYearAndMissingStructureInsteadOfInferringClosures(Market market) throws Exception {
        var data=fixture(market);
        assertThrows(IOException.class,()->HolidayCalendarParser.parse(data,market,2025));
        assertThrows(IOException.class,()->HolidayCalendarParser.parse(json.readTree("{\"stat\":\"ok\"}"),market,2026));
    }
    @Test void rejectsUnknownEventAndTruncatedRowCount() throws Exception {
        var data=fixture(Market.TWSE);
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.path("data").get(0)).set(1,json.getNodeFactory().textNode("未知事件"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.path("data").get(0)).set(2,json.getNodeFactory().textNode(""));
        assertThrows(IOException.class,()->HolidayCalendarParser.parse(data,Market.TWSE,2026));
        var truncated=fixture(Market.TWSE);((com.fasterxml.jackson.databind.node.ArrayNode)truncated.path("data")).remove(1);
        assertThrows(IOException.class,()->HolidayCalendarParser.parse(truncated,Market.TWSE,2026));
    }
    @Test void rejectsNonOkHtmlEvenWhenItContainsAnOldCalendar() throws Exception {
        var data=fixture(Market.TPEX);data.put("stat","error");
        assertThrows(IOException.class,()->HolidayCalendarParser.parse(data,Market.TPEX,2026));
    }
}
