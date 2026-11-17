package tw.holdings.quote;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.HashSet;
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
import tw.holdings.quote.marketstatus.MarketStatusService;
import tw.holdings.quote.marketstatus.MarketStatusService.ListingStatus;

@Service
public class QuoteService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteService.class);
    private final QuoteCacheRepository cache;
    private final OfficialQuoteLookup officialQuotes;
    private final BatchQuoteService batchQuotes;
    private final Clock clock;
    private final MarketStatusService markets;
    private final ExecutorService quoteWorkers;
    private final ConcurrentHashMap<String, CompletableFuture<QuoteResult>> inFlight = new ConcurrentHashMap<>();

    QuoteService(QuoteCacheRepository cache, OfficialQuoteLookup officialQuotes, Clock clock,
                 ExecutorService quoteWorkers, MarketStatusService markets, BatchQuoteService batchQuotes) {
        this.cache = cache;
        this.officialQuotes = officialQuotes;
        this.batchQuotes = batchQuotes;
        this.clock = clock;
        this.markets = markets;
        this.quoteWorkers = quoteWorkers;
    }

    public QuoteResult lookup(String code, Market knownMarket) {
        ZonedDateTime now = taipeiNow();
        var day = now.toLocalDate();
        var listing = markets.quoteListing(code, day).orElse(null);
        if (isDelisted(listing)) {
            return QuoteResult.notFound();
        }
        var today = dailyEntry(day, code, listing);
        if (today != null && today.result().status() != QuoteResult.Status.UNAVAILABLE) {
            if (today.result().status() == QuoteResult.Status.FOUND) {
                queueIfDue(code, knownMarket, now, today);
            }
            return today.result();
        }

        if (!QuoteRefreshPolicy.isWeekday(now)) {
            var prior = latestFound(day, code, listing);
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
        var listing = markets.quoteListing(code, day).orElse(null);
        if (isDelisted(listing)) {
            return QuoteResult.notFound();
        }
        var today = dailyEntry(day, code, listing);
        if (today != null && day.equals(today.result().priceDate())) {
            return today.result();
        }
        var last = latestFound(day, code, listing).orElse(null);
        queueIfDue(code, knownMarket, now, today);
        if (last != null) {
            return last;
        }
        return listing == null ? QuoteResult.unavailable()
                : QuoteResult.found(listing.name(), listing.market(), null, null);
    }

    @Scheduled(fixedDelay = 30_000)
    void refreshPendingCloses() {
        ZonedDateTime now = taipeiNow();
        if (QuoteRefreshPolicy.isPostClose(now)) {
            Set<Market> queuedMarkets = new HashSet<>();
            cache.dueAfterClose(now.toLocalDate(), now.toInstant())
                    .stream().filter(due -> !isDelisted(markets.quoteListing(due.code(), now.toLocalDate()).orElse(null)))
                    .filter(due -> queuedMarkets.add(due.market()))
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
        var listing = markets.quoteListing(code, day).orElse(null);
        if (isDelisted(listing)) {
            return QuoteResult.notFound();
        }
        if (!validation && !QuoteRefreshPolicy.isWeekday(now)) {
            return latestFound(day, code, listing).orElseGet(QuoteResult::unavailable);
        }
        var entry = dailyEntry(day, code, listing);
        if (!QuoteRefreshPolicy.canRefresh(now, entry)) {
            return entry.result();
        }

        var last = latestFound(day, code, listing).orElse(null);
        Market market = listing != null ? listing.market()
                : hint != null ? hint : last == null ? null : last.market();
        boolean postClose = QuoteRefreshPolicy.isPostClose(now);
        boolean onlyCurrentMonth = postClose && last != null && last.close() != null;
        QuoteResult result = batchQuotes.lookup(code, market).orElseGet(() ->
                validation ? officialQuotes.lookup(code, market, day, onlyCurrentMonth) : QuoteResult.unavailable());
        if (listing != null && result.priceDate() != null && result.priceDate().isBefore(listing.effectiveDate())) {
            result = QuoteResult.found(listing.name(), market, null, null);
        }
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

    private QuoteCacheRepository.Entry dailyEntry(LocalDate day, String code, ListingStatus listing) {
        var entry = cache.find(day, code).orElse(null);
        if (entry == null || listing == null) {
            return entry;
        }
        var result = entry.result();
        if (result.status() == QuoteResult.Status.FOUND
                && (result.market() != listing.market()
                || result.priceDate() != null && result.priceDate().isBefore(listing.effectiveDate()))) {
            return null;
        }
        return entry;
    }

    private Optional<QuoteResult> latestFound(LocalDate day, String code, ListingStatus listing) {
        return listing == null ? cache.latestFound(day, code)
                : cache.latestFound(day, code, listing.effectiveDate(), listing.market());
    }

    private boolean isDelisted(ListingStatus listing) {
        return listing != null && listing.delisted();
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
}
