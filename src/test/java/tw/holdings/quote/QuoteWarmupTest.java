package tw.holdings.quote;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
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
class QuoteWarmupTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    static final LocalDate SATURDAY=LocalDate.of(2026,11,21);
    static final LocalDate FRIDAY=SATURDAY.minusDays(1);
    OfficialBatchQuoteClient official;
    Clock clock;
    QuoteCacheRepository cache;
    QuoteTargetDates dates;
    ExecutorService workers;
    BatchQuoteService batches;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM quote_cache");jdbc.update("DELETE FROM quote_market_refresh");
        official=mock(OfficialBatchQuoteClient.class);clock=Clock.fixed(SATURDAY.atTime(10,0).atZone(QuoteRefreshPolicy.TAIPEI).toInstant(),ZoneOffset.UTC);
        cache=new QuoteCacheRepository(jdbc);dates=new QuoteTargetDates();workers=mock(ExecutorService.class);
        doAnswer(call->{call.<Runnable>getArgument(0).run();return null;}).when(workers).execute(any(Runnable.class));
        batches=new BatchQuoteService(official,cache,clock,new TransactionTemplate(tm),new QuoteMarketRefreshRepository(jdbc),new MarketRefreshPolicy(bound->0),dates);
    }
    OfficialBatchQuoteClient.Snapshot snapshot(Market market,LocalDate day){
        String code=market==Market.TWSE?"2330":"6488";
        return new OfficialBatchQuoteClient.Snapshot(day,Map.of(code,QuoteResult.found("測試公司",market,new BigDecimal("100"),day)));
    }
    QuoteWarmup warmup(){return new QuoteWarmup(batches,cache,dates,clock,workers,14);}
    @Test void saturdayColdDatabaseWithoutHoldingsFetchesFridayForBothMarkets() throws Exception {
        for(Market market:Market.values())when(official.fetch(market,FRIDAY)).thenReturn(snapshot(market,FRIDAY));
        var warm=warmup();warm.tick();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM holdings",Integer.class));
        assertEquals(FRIDAY,cache.latestFound(SATURDAY,"2330").orElseThrow().priceDate());
        assertEquals(FRIDAY,cache.latestFound(SATURDAY,"6488").orElseThrow().priceDate());
        warm.tick();warmup().tick();
        for(Market market:Market.values())verify(official,times(1)).fetch(market,FRIDAY);
    }
    @Test void alreadyCachedFridayIsReusedAcrossRestartWithoutARequest() {
        for(Market market:Market.values())cache.saveBatch(FRIDAY.atTime(15,0).atZone(QuoteRefreshPolicy.TAIPEI),snapshot(market,FRIDAY).quotes());
        warmup().tick();verifyNoInteractions(official);
    }
    @Test void missingPastDayFallsBackButNetworkFailureDoesNotProbeOlderDates() throws Exception {
        var missing=new OfficialBatchQuoteClient.BatchUnavailable(new IOException("empty holiday report"),true,true);
        when(official.fetch(Market.TWSE,FRIDAY)).thenThrow(missing);
        when(official.fetch(Market.TWSE,FRIDAY.minusDays(1))).thenReturn(snapshot(Market.TWSE,FRIDAY.minusDays(1)));
        when(official.fetch(Market.TPEX,FRIDAY)).thenThrow(new IOException("offline"));
        warmup().tick();
        assertEquals(FRIDAY.minusDays(1),cache.latestFound(SATURDAY,"2330").orElseThrow().priceDate());
        assertTrue(cache.latestFound(SATURDAY,"6488").isEmpty());
        verify(official,never()).fetch(Market.TPEX,FRIDAY.minusDays(1));
    }
    @Test void concurrentTicksQueueOnlyOneWarmupPerMarketAndReturnImmediately() {
        Queue<Runnable> queue=new ArrayDeque<>();
        doAnswer(call->{queue.add(call.getArgument(0));return null;}).when(workers).execute(any(Runnable.class));
        var warm=warmup();warm.tick();warm.tick();
        assertEquals(2,queue.size());verifyNoInteractions(official);
    }
    @Test void weekdayMorningTargetsThePreviousSession() {
        var monday=SATURDAY.plusDays(2).atTime(10,0).atZone(QuoteRefreshPolicy.TAIPEI);
        assertEquals(FRIDAY,dates.latestExpected(Market.TWSE,monday));
    }
}
