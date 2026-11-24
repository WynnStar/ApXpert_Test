package tw.holdings.quote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteRefreshPolicyTest {
    private static final LocalDate DAY=LocalDate.of(2026,11,17);
    @Test void normalWindowThenLowFrequencyContinuesAfter1430() {
        assertEquals(0,QuoteRefreshPolicy.refreshSlot(at(13,39)));
        assertEquals(1,QuoteRefreshPolicy.refreshSlot(at(13,40)));
        assertEquals(2,QuoteRefreshPolicy.refreshSlot(at(14,30)));
        assertEquals(at(13,42).toInstant(),QuoteRefreshPolicy.nextAttemptAt(at(13,40),false));
        assertEquals(at(14,40).toInstant(),QuoteRefreshPolicy.nextAttemptAt(at(14,30),false));
    }
    @Test void nextAttemptAndPublishedPriceGateRepeatedPageVisits() {
        var old=QuoteResult.found("台積電",Market.TWSE,new BigDecimal("100"),DAY.minusDays(1));
        var entry=new QuoteCacheRepository.Entry(old,0,1,at(13,42).toInstant(),at(13,40).toInstant());
        assertFalse(QuoteRefreshPolicy.canRefresh(at(13,41),entry));
        assertTrue(QuoteRefreshPolicy.canRefresh(at(13,42),entry));
        var fresh=new QuoteCacheRepository.Entry(QuoteResult.found("台積電",Market.TWSE,new BigDecimal("110"),DAY),0,1,null);
        assertFalse(QuoteRefreshPolicy.canRefresh(at(16,0),fresh));
    }
    @Test void weekendIsNotARegularPostCloseSession() {
        assertFalse(QuoteRefreshPolicy.isPostClose(at(14,0).plusDays(4)));
        assertFalse(QuoteRefreshPolicy.isPostClose(at(14,0).plusDays(5)));
    }
    private static ZonedDateTime at(int h,int m){return DAY.atTime(h,m).atZone(QuoteRefreshPolicy.TAIPEI);}
}
