package tw.holdings.quote;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class QuoteService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteService.class);
    private final QuoteCacheRepository cache;
    private final List<QuoteSource> sources;
    private final Clock clock;
    private final ExecutorService quoteWorkers;
    private final ConcurrentHashMap<String, CompletableFuture<QuoteResult>> inFlight = new ConcurrentHashMap<>();

    QuoteService(QuoteCacheRepository cache, List<QuoteSource> sources, Clock clock,
                 ExecutorService quoteWorkers) {
        this.cache = cache;
        this.sources = sources.stream().sorted(Comparator.comparing(QuoteSource::market)).toList();
        this.clock = clock;
        this.quoteWorkers = quoteWorkers;
    }

    public QuoteResult lookup(String code, Market knownMarket) {
        ZonedDateTime now = taipeiNow();
        var day = now.toLocalDate();
        var today = cache.find(day, code).orElse(null);
        if (today != null && today.result().status() != QuoteResult.Status.UNAVAILABLE) {
            if (today.result().status() == QuoteResult.Status.FOUND) {
                queueIfDue(code, knownMarket, now, today);
            }
            return today.result();
        }

        if (!QuoteRefreshPolicy.isWeekday(now)) {
            var prior = cache.latestFound(day, code);
            if (prior.isPresent()) {
                return prior.get();
            }
        }
        if (!QuoteRefreshPolicy.canRefresh(now, today)) {
            return QuoteResult.unavailable();
        }
        try {
            return submit(code, knownMarket, true).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return QuoteResult.unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            return QuoteResult.unavailable();
        }
    }

    public QuoteResult forDisplay(String code, Market knownMarket) {
        ZonedDateTime now = taipeiNow();
        var day = now.toLocalDate();
        var today = cache.find(day, code).orElse(null);
        if (today != null && day.equals(today.result().priceDate())) {
            return today.result();
        }
        var last = cache.latestFound(day, code).orElse(null);
        queueIfDue(code, knownMarket, now, today);
        return last == null ? QuoteResult.unavailable() : last;
    }

    @Scheduled(fixedDelay = 30_000)
    void refreshPendingCloses() {
        ZonedDateTime now = taipeiNow();
        if (QuoteRefreshPolicy.isPostClose(now)) {
            cache.dueAfterClose(now.toLocalDate(), now.toInstant())
                    .forEach(due -> submit(due.code(), due.market(), false));
        }
    }

    private void queueIfDue(String code, Market market, ZonedDateTime now, QuoteCacheRepository.Entry today) {
        if (QuoteRefreshPolicy.isWeekday(now) && QuoteRefreshPolicy.canRefresh(now, today)) {
            submit(code, market, false);
        }
    }

    private CompletableFuture<QuoteResult> submit(String code, Market hint, boolean validation) {
        var future = new CompletableFuture<QuoteResult>();
        var pending = inFlight.putIfAbsent(code, future);
        if (pending != null) {
            return pending;
        }
        try {
            quoteWorkers.execute(() -> {
                try {
                    QuoteResult result = refresh(code, hint, validation);
                    inFlight.remove(code, future);
                    future.complete(result);
                } catch (RuntimeException exception) {
                    LOGGER.error("Quote refresh failed for {}", code, exception);
                    inFlight.remove(code, future);
                    future.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException saturated) {
            inFlight.remove(code, future);
            future.complete(QuoteResult.unavailable());
        }
        return future;
    }

    private QuoteResult refresh(String code, Market hint, boolean validation) {
        ZonedDateTime now = taipeiNow();
        var day = now.toLocalDate();
        if (!validation && !QuoteRefreshPolicy.isWeekday(now)) {
            return cache.latestFound(day, code).orElseGet(QuoteResult::unavailable);
        }
        var entry = cache.find(day, code).orElse(null);
        if (!QuoteRefreshPolicy.canRefresh(now, entry)) {
            return entry.result();
        }

        var last = cache.latestFound(day, code).orElse(null);
        Market market = hint != null ? hint : last == null ? null : last.market();
        boolean postClose = QuoteRefreshPolicy.isPostClose(now);
        boolean onlyCurrentMonth = postClose && last != null && last.close() != null;
        QuoteResult result = lookupOfficial(code, market, day, onlyCurrentMonth);
        if (result.status() == QuoteResult.Status.NOT_FOUND && market != null) {
            result = QuoteResult.unavailable();
        }

        boolean failed = result.status() == QuoteResult.Status.UNAVAILABLE;
        int failures = failed ? (entry == null ? 1 : entry.failureCount() + 1) : 0;
        int attempts = (entry == null ? 0 : entry.postCloseAttempts()) + (postClose ? 1 : 0);
        var next = now.toInstant().plus(QuoteRefreshPolicy.retryDelay(postClose, failures));
        QuoteResult retained = keepLatest(result, last);
        cache.save(day, code, new QuoteCacheRepository.Entry(retained, failures, attempts, next), now.toInstant());
        return failed ? result : retained;
    }

    private QuoteResult keepLatest(QuoteResult received, QuoteResult existing) {
        if (existing == null || received.status() == QuoteResult.Status.NOT_FOUND) {
            return received;
        }
        if (received.status() == QuoteResult.Status.UNAVAILABLE
                || (existing.priceDate() != null && (received.priceDate() == null
                || received.priceDate().isBefore(existing.priceDate())))) {
            return existing;
        }
        return received;
    }

    private ZonedDateTime taipeiNow() {
        return clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
    }

    private QuoteResult lookupOfficial(String code, Market knownMarket, LocalDate day, boolean onlyCurrentMonth) {
        Set<Market> unavailable = EnumSet.noneOf(Market.class);
        QuoteResult identity = null;
        YearMonth current = YearMonth.from(day);
        for (YearMonth month : (onlyCurrentMonth ? List.of(current) : List.of(current, current.minusMonths(1)))) {
            for (QuoteSource source : sources) {
                Market preferred = identity == null ? knownMarket : identity.market();
                if (unavailable.contains(source.market()) || (preferred != null && preferred != source.market())) {
                    continue;
                }
                QuoteResult result = fetch(source, code, month, day);
                switch (result.status()) {
                    case FOUND -> {
                        if (result.close() != null) {
                            return result;
                        }
                        identity = result;
                    }
                    case UNAVAILABLE -> unavailable.add(source.market());
                    case NOT_FOUND -> {
                        // 查無當月資料時可能是月初休市，仍須檢查上個月。
                    }
                }
            }
        }
        if (identity != null) {
            return identity;
        }
        return unavailable.isEmpty() ? QuoteResult.notFound() : QuoteResult.unavailable();
    }

    private QuoteResult fetch(QuoteSource source, String code, YearMonth month, LocalDate day) {
        try {
            return source.fetch(code, month, day);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return QuoteResult.unavailable();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Official quote unavailable: market={}, code={}, month={}, reason={}",
                    source.market(), code, month, exception.getClass().getSimpleName());
            return QuoteResult.unavailable();
        }
    }
}
