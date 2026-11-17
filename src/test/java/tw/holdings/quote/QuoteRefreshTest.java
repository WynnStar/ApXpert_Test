package tw.holdings.quote;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tw.holdings.quote.client.QuoteSource;
import tw.holdings.quote.marketstatus.MarketStatusService;
import tw.holdings.quote.marketstatus.MarketStatusService.ListingStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@JdbcTest
@Import({OfficialQuoteLookup.class, QuoteRefreshTest.QuoteSources.class})
class QuoteRefreshTest {
    private static final String CODE = "2330";
    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 17);
    private static final QuoteResult YESTERDAY = found(TUESDAY.minusDays(1), "100.00");
    private static final QuoteResult TODAY = found(TUESDAY, "110.00");

    @Autowired JdbcTemplate jdbc;
    private final TestClock clock = new TestClock();
    @Autowired Source source;
    @Autowired OfficialQuoteLookup officialQuotes;
    private final BatchQuoteService batches = mock(BatchQuoteService.class);
    private final MarketStatusService markets = mock(MarketStatusService.class);
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final ExecutorService workers = mock(ExecutorService.class);
    private QuoteCacheRepository cache;
    private QuoteService quotes;

    @BeforeEach
    void setup() {
        clock.set(TUESDAY, 10, 0);
        source.calls = 0;
        source.result = QuoteResult.notFound();
        when(batches.lookup(any(), any())).thenAnswer(invocation -> {
            source.calls++;
            return Optional.of(source.result);
        });
        cache = new QuoteCacheRepository(jdbc);
        quotes = new QuoteService(cache, officialQuotes, clock, workers, markets, batches);
        doAnswer(task -> { task.<Runnable>getArgument(0).run(); return null; })
                .when(workers).execute(any(Runnable.class));
    }

    @Test
    void confirmedDelistingSuppressesAllNormalRequestsAndRelistingIgnoresOldPrices() {
        seed(TUESDAY, YESTERDAY);
        when(markets.quoteListing(CODE, TUESDAY)).thenReturn(
                Optional.of(new ListingStatus(Market.TWSE, TUESDAY, "台積電", true)));
        clock.set(TUESDAY, 14, 0);
        assertEquals(QuoteResult.Status.NOT_FOUND, quotes.forDisplay(CODE, Market.TWSE).status());
        assertEquals(QuoteResult.Status.NOT_FOUND, quotes.lookup(CODE, Market.TWSE).status());
        quotes.refreshPendingCloses();
        assertEquals(0, source.calls);

        var wednesday = TUESDAY.plusDays(1);
        clock.set(wednesday, 14, 0);
        when(markets.quoteListing(CODE, wednesday)).thenReturn(
                Optional.of(new ListingStatus(Market.TWSE, wednesday, "台積電", false)));
        source.result = YESTERDAY;
        var fromNewMarket = quotes.forDisplay(CODE, Market.TPEX);
        assertEquals(Market.TWSE, fromNewMarket.market());
        assertNull(fromNewMarket.close());
        assertNull(quotes.lookup(CODE, Market.TPEX).close());
        assertEquals(1, source.calls);
        clock.advance(301);
        source.result = found(wednesday, "112.00");
        quotes.refreshPendingCloses();
        assertEquals(new BigDecimal("112.00"), quotes.forDisplay(CODE, Market.TWSE).close());
        assertEquals(2, source.calls);
    }

    @Test
    void retriesFirstFailureAfterThirtySecondsAndBacksOffRepeatedFailures() {
        source.result = QuoteResult.unavailable();
        assertEquals(QuoteResult.Status.UNAVAILABLE, quotes.lookup(CODE, null).status());
        clock.advance(29);
        quotes.lookup(CODE, null);
        assertEquals(1, source.calls);
        clock.advance(1);
        quotes.lookup(CODE, null);
        assertEquals(2, source.calls);
        clock.advance(59);
        quotes.lookup(CODE, null);
        assertEquals(2, source.calls);

        source.result = YESTERDAY;
        clock.advance(1);
        assertEquals(YESTERDAY, quotes.lookup(CODE, null));
        assertEquals(3, source.calls);
        assertEquals(0, cache.find(TUESDAY, CODE).orElseThrow().failureCount());
    }

    @Test
    void backgroundRefreshIsCoalescedAndReturnsCachedValueWithoutWaiting() {
        seed(TUESDAY, YESTERDAY);
        defersWork();
        clock.set(TUESDAY, 13, 30);
        source.result = TODAY;

        assertEquals(YESTERDAY, quotes.forDisplay(CODE, Market.TWSE));
        assertEquals(YESTERDAY, quotes.forDisplay(CODE, Market.TWSE));
        quotes.refreshPendingCloses();
        assertEquals(1, queue.size());
        assertEquals(0, source.calls);
        queue.remove().run();

        assertEquals(TODAY, quotes.forDisplay(CODE, Market.TWSE));
        clock.advance(300);
        quotes.refreshPendingCloses();
        assertEquals(1, source.calls);
        assertEquals(0, queue.size());
    }

    @Test
    void stopsAfterFourPostCloseAttemptsAndKeepsLatestKnownPrice() {
        seed(TUESDAY, YESTERDAY);
        clock.set(TUESDAY, 13, 30);
        source.result = QuoteResult.unavailable();
        for (int attempt = 1; attempt <= 4; attempt++) {
            quotes.refreshPendingCloses();
            assertEquals(attempt, source.calls);
            assertEquals(YESTERDAY, quotes.forDisplay(CODE, Market.TWSE));
            clock.advance(299);
            quotes.refreshPendingCloses();
            assertEquals(attempt, source.calls);
            clock.advance(1);
        }
        quotes.refreshPendingCloses();
        assertEquals(YESTERDAY, quotes.forDisplay(CODE, Market.TWSE));
        assertEquals(4, source.calls);

        // 模擬應用程式換新實例，重試次數與最後行情仍從資料庫取得。
        var restarted = new QuoteService(cache, officialQuotes, clock, workers, markets, batches);
        restarted.refreshPendingCloses();
        assertEquals(YESTERDAY, restarted.forDisplay(CODE, Market.TWSE));
        assertEquals(4, source.calls);
    }

    @Test
    void weekendUsesPreviousPriceAndStillVerifiesUnknownCodeOnDemand() {
        seed(TUESDAY, YESTERDAY);
        clock.set(TUESDAY.plusDays(4), 15, 0);
        assertEquals(YESTERDAY, quotes.forDisplay(CODE, Market.TWSE));
        assertEquals(YESTERDAY, quotes.lookup(CODE, null));
        quotes.refreshPendingCloses();
        assertEquals(0, source.calls);

        source.result = TODAY;
        assertEquals(TODAY, quotes.lookup("6488", null));
        assertEquals(1, source.calls);
    }

    @Test
    void unknownCodeIsCachedForTheDay() {
        source.result = QuoteResult.notFound();
        assertEquals(QuoteResult.Status.NOT_FOUND, quotes.lookup("0000", null).status());
        int attempts = source.calls;
        clock.set(TUESDAY, 14, 0);
        assertEquals(QuoteResult.Status.NOT_FOUND, quotes.lookup("0000", null).status());
        quotes.refreshPendingCloses();
        assertEquals(attempts, source.calls);
    }

    private void seed(LocalDate day, QuoteResult result) {
        jdbc.update("INSERT INTO holdings (code, lots, buy_price, v2_managed) VALUES (?, 1, 100, FALSE)", CODE);
        cache.save(day, CODE, new QuoteCacheRepository.Entry(result, 0, 0, clock.instant()), clock.instant());
    }

    private void defersWork() {
        doAnswer(task -> { queue.add(task.getArgument(0)); return null; })
                .when(workers).execute(any(Runnable.class));
    }

    private static QuoteResult found(LocalDate day, String close) {
        return new QuoteResult(QuoteResult.Status.FOUND, "台積電", Market.TWSE, new BigDecimal(close), day);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QuoteSources {
        @Bean
        Source quoteRefreshSource() {
            return new Source();
        }
    }

    private static class Source implements QuoteSource {
        private QuoteResult result;
        private int calls;

        @Override
        public Market market() {
            return Market.TWSE;
        }

        @Override
        public QuoteResult fetch(String code, YearMonth month, LocalDate today) throws IOException {
            calls++;
            if (result.status() == QuoteResult.Status.UNAVAILABLE) {
                throw new IOException("simulated connection failure");
            }
            return result;
        }
    }

    private static class TestClock extends Clock {
        private Instant current;

        void set(LocalDate day, int hour, int minute) {
            current = LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(getZone()).toInstant();
        }

        void advance(long seconds) {
            current = current.plus(Duration.ofSeconds(seconds));
        }

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
