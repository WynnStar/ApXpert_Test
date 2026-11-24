package tw.holdings.quote;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.client.OfficialBatchQuoteClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@JdbcTest
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MarketRefreshStateTest {
    static final LocalDate DAY=LocalDate.of(2026,11,24);
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    MutableClock clock=new MutableClock();
    OfficialBatchQuoteClient official;
    QuoteMarketRefreshRepository states;
    QuoteCacheRepository cache;
    BatchQuoteService service;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM quote_cache");jdbc.update("DELETE FROM quote_market_refresh");
        official=mock(OfficialBatchQuoteClient.class);states=new QuoteMarketRefreshRepository(jdbc);
        cache=new QuoteCacheRepository(jdbc);service=restart();clock.set(13,40,0);
    }
    BatchQuoteService restart(){return new BatchQuoteService(official,cache,clock,new TransactionTemplate(tm),states,new MarketRefreshPolicy(bound->0));}
    IOException unpublished(){return new OfficialBatchQuoteClient.BatchUnavailable(new IOException("not published"),true,true);}
    OfficialBatchQuoteClient.Snapshot today(){return new OfficialBatchQuoteClient.Snapshot(DAY,Map.of("2330",QuoteResult.found("台積電",Market.TWSE,new BigDecimal("100"),DAY)));}
    @Test void firstJitterIsPersistedAndNormalRetryIsNotThirtyMinutes() throws Exception {
        when(official.fetch(Market.TWSE,DAY)).thenThrow(unpublished()).thenReturn(today());
        clock.set(13,40,14);assertFalse(service.ensure(Market.TWSE,DAY));verifyNoInteractions(official);
        var saved=states.find(Market.TWSE,DAY).orElseThrow();
        assertEquals(DAY.atTime(13,40,15).atZone(clock.getZone()).toInstant(),saved.windowStart());
        clock.set(13,40,15);assertFalse(restart().ensure(Market.TWSE,DAY));
        assertNotNull(states.find(Market.TWSE,DAY).orElseThrow().firstResponse());
        clock.set(13,42,14);assertFalse(restart().ensure(Market.TWSE,DAY));
        clock.set(13,42,15);assertTrue(restart().ensure(Market.TWSE,DAY));
        clock.set(16,0,0);assertTrue(restart().ensure(Market.TWSE,DAY));
        verify(official,times(2)).fetch(Market.TWSE,DAY);
    }
    @Test void publicationAfter1430IsFoundWithoutAUserPageVisitOrRestart() throws Exception {
        when(official.fetch(Market.TWSE,DAY)).thenThrow(unpublished()).thenThrow(unpublished()).thenReturn(today());
        clock.set(13,40,15);service.ensure(Market.TWSE,DAY);
        clock.set(14,30,0);service.ensure(Market.TWSE,DAY);
        assertEquals(DAY.atTime(14,40).atZone(clock.getZone()).toInstant(),states.find(Market.TWSE,DAY).orElseThrow().nextAttempt());
        clock.set(14,39,59);assertFalse(service.ensure(Market.TWSE,DAY));
        clock.set(14,40,0);assertTrue(service.ensure(Market.TWSE,DAY));
        assertEquals(DAY,cache.latestFound(DAY,"2330").orElseThrow().priceDate());
    }
    @Test void completeOutageStartsOnlyOneRecoveryWindowAndRestartDoesNotResetIt() throws Exception {
        when(official.fetch(Market.TWSE,DAY)).thenThrow(new IOException("offline"))
                .thenThrow(new IOException("offline")) .thenThrow(unpublished())
                .thenThrow(new IOException("offline again")).thenThrow(unpublished());
        clock.set(13,40,15);service.ensure(Market.TWSE,DAY);
        clock.set(14,10,15);restart().ensure(Market.TWSE,DAY);
        clock.set(14,40,15);restart().ensure(Market.TWSE,DAY);
        var started=clock.instant();assertEquals(started,states.find(Market.TWSE,DAY).orElseThrow().recoveryStarted());
        clock.set(14,42,15);restart().ensure(Market.TWSE,DAY);
        clock.set(15,12,15);restart().ensure(Market.TWSE,DAY);
        var s=states.find(Market.TWSE,DAY).orElseThrow();
        assertEquals(started,s.recoveryStarted());assertEquals(clock.instant().plusSeconds(600),s.nextAttempt());
    }
    @Test void recognizableUnpublishedResponseIsNotAnEntireWindowOutage() throws Exception {
        when(official.fetch(Market.TWSE,DAY)).thenThrow(unpublished());
        clock.set(13,40,15);service.ensure(Market.TWSE,DAY);
        clock.set(14,40,0);restart().ensure(Market.TWSE,DAY);
        assertNull(states.find(Market.TWSE,DAY).orElseThrow().recoveryStarted());
    }
    @Test void emptyDatabaseFailureStillPersistsDeadline() throws Exception {
        when(official.fetch(Market.TWSE,DAY)).thenThrow(new IOException("offline"));
        clock.set(13,40,15);service.ensure(Market.TWSE,DAY);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quote_cache",Integer.class));
        clock.set(13,41,0);restart().ensure(Market.TWSE,DAY);
        verify(official,times(1)).fetch(Market.TWSE,DAY);
    }
    static class MutableClock extends Clock {
        Instant value;
        void set(int h,int m,int s){value=DAY.atTime(h,m,s).atZone(getZone()).toInstant();}
        public ZoneId getZone(){return ZoneId.of("Asia/Taipei");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
}
