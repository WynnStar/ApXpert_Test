package tw.holdings.quote;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.client.OfficialBatchQuoteClient;
import tw.holdings.quote.marketstatus.MarketStatusService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@JdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BatchQuoteServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private final MutableClock clock = new MutableClock();
    private OfficialBatchQuoteClient official;
    private QuoteCacheRepository cache;
    private BatchQuoteService batches;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM quote_cache");
        jdbc.update("DELETE FROM quote_market_refresh");
        clock.current = DAY.atTime(14, 0).atZone(clock.getZone()).toInstant();
        official = mock(OfficialBatchQuoteClient.class);
        cache = new QuoteCacheRepository(jdbc);
        batches = new BatchQuoteService(official, cache, clock, new TransactionTemplate(transactionManager), new QuoteMarketRefreshRepository(jdbc), new MarketRefreshPolicy(bound -> 0), new QuoteTargetDates(new TradingCalendarService(new TradingCalendarRepository(jdbc),"","")));
    }

    @Test
    void concurrentDifferentStocksShareOneMarketRequestAndPersistBothPrices() throws Exception {
        var snapshot = snapshot(DAY.minusDays(1));
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Callable<QuoteResult>>();
            for (int index = 0; index < 20; index++) {
                String code = index % 2 == 0 ? "2330" : "0050";
                calls.add(() -> {
                    start.await();
                    return batches.lookup(code, Market.TWSE).orElseThrow();
                });
            }
            var futures = new ArrayList<java.util.concurrent.Future<QuoteResult>>();
            for (var call : calls) {
                futures.add(workers.submit(call));
            }
            start.countDown();
            for (var future : futures) {
                assertEquals(DAY.minusDays(1), future.get(10, TimeUnit.SECONDS).priceDate());
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        verify(official, times(1)).fetch(Market.TWSE, DAY);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quote_cache", Integer.class));
        assertEquals(new BigDecimal("100.00"), cache.find(DAY, "2330").orElseThrow().result().close());
        assertEquals(new BigDecimal("150.00"), cache.find(DAY, "0050").orElseThrow().result().close());
    }

    @Test
    void olderBatchCannotOverwriteNewerPriceFromAnEarlierLookupDay() throws Exception {
        QuoteResult recent = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("110.00"), DAY.minusDays(1));
        cache.save(DAY.minusDays(1), "2330",
                new QuoteCacheRepository.Entry(recent, 0, 0, clock.instant()), clock.instant());
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot(DAY.minusDays(2)));

        assertEquals(recent, batches.lookup("2330", Market.TWSE).orElseThrow());
        assertEquals(recent, cache.find(DAY, "2330").orElseThrow().result());
        assertEquals(DAY.minusDays(2), batches.lookup("0050", Market.TWSE).orElseThrow().priceDate());
        verify(official, times(1)).fetch(Market.TWSE, DAY);
    }

    @Test
    void missingStockDoesNotBecomeANegativeIdentityCacheEntry() throws Exception {
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot(DAY.minusDays(1)));

        assertTrue(batches.lookup("9999", Market.TWSE).isEmpty());
        assertTrue(cache.find(DAY, "9999").isEmpty());
        assertTrue(batches.lookup("9998", Market.TWSE).isEmpty());
        verify(official, times(1)).fetch(Market.TWSE, DAY);
    }

    @Test
    void failedBatchRetainsPricesAndSharesThirtyMinuteBackoffAcrossStocks() throws Exception {
        QuoteResult recent = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("90.00"), DAY.minusDays(2));
        cache.save(DAY.minusDays(1), "2330", new QuoteCacheRepository.Entry(recent, 0, 0, clock.instant()), clock.instant());
        clock.current=DAY.atTime(10,0).atZone(clock.getZone()).toInstant();
        when(official.fetch(eq(Market.TWSE), any())).thenThrow(new IOException("simulated failure"))
                .thenReturn(snapshot(DAY.minusDays(1)));

        assertEquals(recent, batches.lookup("2330", Market.TWSE).orElseThrow());
        assertEquals(recent, cache.latestFound(DAY, "2330").orElseThrow());
        assertTrue(batches.lookup("0050", Market.TWSE).isEmpty());
        clock.current = clock.current.plusSeconds(1799);
        assertTrue(batches.lookup("0050", Market.TWSE).isEmpty());
        verify(official, times(1)).fetch(Market.TWSE, DAY.minusDays(1));
        clock.current = clock.current.plusSeconds(1);
        assertEquals(new BigDecimal("100.00"), batches.lookup("2330", Market.TWSE).orElseThrow().close());
        verify(official, times(2)).fetch(Market.TWSE, DAY.minusDays(1));
    }

    @Test
    void marketRetryDeadlineRemainsSharedAcrossStocksAndApplicationRestart() throws Exception {
        when(official.fetch(eq(Market.TWSE),any())).thenReturn(snapshot(DAY.minusDays(1)));
        clock.current=DAY.atTime(14,20).atZone(clock.getZone()).toInstant();
        assertTrue(batches.lookup("2330",Market.TWSE).isPresent());
        var restarted=new BatchQuoteService(official,cache,clock,new TransactionTemplate(transactionManager),new QuoteMarketRefreshRepository(jdbc),new MarketRefreshPolicy(bound->0),new QuoteTargetDates(new TradingCalendarService(new TradingCalendarRepository(jdbc),"","")));
        clock.current=clock.current.plusSeconds(119);
        assertTrue(restarted.lookup("0050",Market.TWSE).isPresent());
        verify(official,times(1)).fetch(Market.TWSE,DAY);
        clock.current=clock.current.plusSeconds(1);
        restarted.lookup("0050",Market.TWSE);
        verify(official,times(2)).fetch(Market.TWSE,DAY);
        clock.current=DAY.atTime(14,40).atZone(clock.getZone()).toInstant();
        restarted.lookup("0050",Market.TWSE);
        var state=new QuoteMarketRefreshRepository(jdbc).find(Market.TWSE,DAY).orElseThrow();
        assertEquals(DAY.atTime(14,50).atZone(clock.getZone()).toInstant(),state.nextAttempt());
    }

    @Test
    void requestThatFinishesAfterFirstSlotRecordsTheCompletionTime() throws Exception {
        clock.current = DAY.atTime(13, 29).atZone(clock.getZone()).toInstant();
        when(official.fetch(eq(Market.TWSE), any())).thenAnswer(call -> {
            clock.current = DAY.atTime(13, 41).atZone(clock.getZone()).toInstant();
            return snapshot(DAY.minusDays(1));
        });

        assertTrue(batches.lookup("2330", Market.TWSE).isPresent());
        var entry = cache.find(DAY, "2330").orElseThrow();
        assertEquals(clock.instant(), entry.lastAttemptAt());
        assertEquals(1, entry.postCloseAttempts());
        assertEquals(DAY.atTime(13, 43).atZone(clock.getZone()).toInstant(), entry.nextAttemptAt());

        var restarted = new BatchQuoteService(official, cache, clock, new TransactionTemplate(transactionManager), new QuoteMarketRefreshRepository(jdbc), new MarketRefreshPolicy(bound -> 0), new QuoteTargetDates(new TradingCalendarService(new TradingCalendarRepository(jdbc),"","")));
        clock.current = DAY.atTime(13, 42).atZone(clock.getZone()).toInstant();
        assertTrue(restarted.lookup("0050", Market.TWSE).isPresent());
        verify(official, times(1)).fetch(Market.TWSE, DAY.minusDays(1));
    }

    @Test
    void batchWriteFailureRollsBackAllRows() throws Exception {
        var invalid = QuoteResult.found("X".repeat(256), Market.TWSE, new BigDecimal("10.00"), DAY);
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(
                new OfficialBatchQuoteClient.Snapshot(DAY,
                        Map.of("2330", snapshot(DAY).quotes().get("2330"), "0050", invalid)));

        assertThrows(DataIntegrityViolationException.class, () -> batches.lookup("2330", Market.TWSE));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quote_cache", Integer.class));
    }

    @Test
    void publishedTodaysBatchStopsFurtherMarketRefreshes() throws Exception {
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot(DAY));

        assertTrue(batches.lookup("2330", Market.TWSE).isPresent());
        clock.current = DAY.atTime(15, 0).atZone(clock.getZone()).toInstant();
        assertTrue(batches.lookup("0050", Market.TWSE).isPresent());
        verify(official, times(1)).fetch(Market.TWSE, DAY);
    }

    @Test
    void normalLookupAndDisplayUseSharedBatchWithoutIndividualHistoryCalls() throws Exception {
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot(DAY));
        var historical = mock(OfficialQuoteLookup.class);
        var workers = mock(ExecutorService.class);
        doAnswer(task -> { task.<Runnable>getArgument(0).run(); return null; })
                .when(workers).execute(any(Runnable.class));
        var quotes = new QuoteService(cache, historical, clock, workers, mock(MarketStatusService.class), batches);

        assertEquals(new BigDecimal("100.00"), quotes.lookup("2330", Market.TWSE).close());
        assertEquals(new BigDecimal("150.00"), quotes.forDisplay("0050", Market.TWSE).close());
        verify(official, times(1)).fetch(Market.TWSE, DAY);
        verify(historical, never()).lookup(any(), any(), any(), anyBoolean());
    }

    @Test
    void missingBatchCodeStillUsesIndividualIdentityValidation() throws Exception {
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot(DAY));
        when(official.fetch(eq(Market.TPEX), any())).thenReturn(
                new OfficialBatchQuoteClient.Snapshot(DAY, Map.of(
                        "6488", QuoteResult.found("環球晶", Market.TPEX, new BigDecimal("100.00"), DAY))));
        var historical = mock(OfficialQuoteLookup.class);
        when(historical.lookup(eq("9999"), isNull(), eq(DAY), anyBoolean()))
                .thenReturn(QuoteResult.notFound());
        var workers = mock(ExecutorService.class);
        doAnswer(task -> { task.<Runnable>getArgument(0).run(); return null; })
                .when(workers).execute(any(Runnable.class));
        var quotes = new QuoteService(cache, historical, clock, workers, mock(MarketStatusService.class), batches);

        assertEquals(QuoteResult.Status.NOT_FOUND, quotes.lookup("9999", null).status());
        verify(historical).lookup(eq("9999"), isNull(), eq(DAY), anyBoolean());
    }


    @Test
    void batchCannotOverwritePriceCommittedByAnotherTransactionAfterItsRead() throws Exception {
        var latest = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("110.00"), DAY);
        var batchJdbc = org.mockito.Mockito.spy(jdbc);
        var independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        doAnswer(call -> {
            independent.executeWithoutResult(status -> cache.save(DAY, "2330",
                    new QuoteCacheRepository.Entry(latest, 0, 0, clock.instant()), clock.instant()));
            return call.callRealMethod();
        }).when(batchJdbc).batchUpdate(any(String.class), org.mockito.ArgumentMatchers.<Object[]>anyList());
        var raceCache = new QuoteCacheRepository(batchJdbc);
        var raceBatch = new BatchQuoteService(official, raceCache, clock,
                new TransactionTemplate(transactionManager), new QuoteMarketRefreshRepository(jdbc), new MarketRefreshPolicy(bound -> 0), new QuoteTargetDates(new TradingCalendarService(new TradingCalendarRepository(jdbc),"","")));
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(snapshot(DAY.minusDays(1)));

        assertEquals(latest, raceBatch.lookup("2330", Market.TWSE).orElseThrow());
        assertEquals(latest, cache.find(DAY, "2330").orElseThrow().result());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM quote_cache", Integer.class));
    }

    @Test
    void olderIndividualQuoteCannotReplaceANewerBatchPrice() {
        var latest = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("110.00"), DAY);
        var older = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("90.00"), DAY.minusDays(1));
        cache.save(DAY, "2330", new QuoteCacheRepository.Entry(latest, 0, 0, clock.instant()), clock.instant());
        cache.save(DAY, "2330", new QuoteCacheRepository.Entry(older, 0, 0, clock.instant()), clock.instant());
        assertEquals(latest, cache.find(DAY, "2330").orElseThrow().result());
    }

    @Test
    void missingBatchPricePreservesActualPreviousTradeDate() throws Exception {
        var previous = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("90.00"), DAY.minusDays(2));
        cache.save(DAY.minusDays(1), "2330", new QuoteCacheRepository.Entry(previous, 0, 0, clock.instant()), clock.instant());
        when(official.fetch(eq(Market.TWSE), any())).thenReturn(new OfficialBatchQuoteClient.Snapshot(
                DAY, Map.of("2330", QuoteResult.found("台積電", Market.TWSE, null, null),
                        "0050", snapshot(DAY).quotes().get("0050"))));

        assertEquals(previous, batches.lookup("2330", Market.TWSE).orElseThrow());
        assertEquals(DAY.minusDays(2), cache.find(DAY, "2330").orElseThrow().result().priceDate());
    }


    @Test
    void missingPriceCannotEraseValidPriceButSameDayCorrectionCanUpdateIt() {
        var original = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("110.00"), DAY);
        var missing = QuoteResult.found("台積電", Market.TWSE, null, null);
        var corrected = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("111.00"), DAY);
        cache.save(DAY, "2330", new QuoteCacheRepository.Entry(original, 0, 0, clock.instant()), clock.instant());
        cache.save(DAY, "2330", new QuoteCacheRepository.Entry(missing, 0, 0, clock.instant()), clock.instant());
        assertEquals(original, cache.find(DAY, "2330").orElseThrow().result());
        cache.save(DAY, "2330", new QuoteCacheRepository.Entry(corrected, 0, 0, clock.instant()), clock.instant());
        assertEquals(corrected, cache.find(DAY, "2330").orElseThrow().result());
    }

    private static OfficialBatchQuoteClient.Snapshot snapshot(LocalDate priceDate) {
        return new OfficialBatchQuoteClient.Snapshot(priceDate, Map.of(
                "2330", QuoteResult.found("台積電", Market.TWSE, new BigDecimal("100.00"), priceDate),
                "0050", QuoteResult.found("元大台灣50", Market.TWSE, new BigDecimal("150.00"), priceDate)));
    }

    private static class MutableClock extends Clock {
        private Instant current;

        @Override
        public ZoneId getZone() {
            return QuoteRefreshPolicy.TAIPEI;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
