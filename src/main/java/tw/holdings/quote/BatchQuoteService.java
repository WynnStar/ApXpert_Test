package tw.holdings.quote;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.client.OfficialBatchQuoteClient;

@Service
class BatchQuoteService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchQuoteService.class);

    private final OfficialBatchQuoteClient official;
    private final QuoteCacheRepository cache;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Map<Market, MarketRefresh> refreshes = new EnumMap<>(Market.class);

    BatchQuoteService(OfficialBatchQuoteClient official, QuoteCacheRepository cache,
                      Clock clock, TransactionTemplate transactions) {
        this.official = official;
        this.cache = cache;
        this.clock = clock;
        this.transactions = transactions;
        for (Market market : Market.values()) {
            refreshes.put(market, new MarketRefresh());
        }
    }

    Optional<QuoteResult> lookup(String code, Market knownMarket) {
        if (knownMarket != null) {
            return lookupMarket(code, knownMarket);
        }
        for (Market market : Market.values()) {
            var result = lookupMarket(code, market);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<QuoteResult> lookupMarket(String code, Market market) {
        var refresh = refreshes.get(market);
        synchronized (refresh) {
            ZonedDateTime now = clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
            LocalDate day = now.toLocalDate();
            if (!day.equals(refresh.day)) {
                refresh.day = day;
                refresh.entry = cache.lastMarketAttempt(day, market).orElse(null);
            }
            if (QuoteRefreshPolicy.canRefresh(now, refresh.entry)) {
                refresh(market, refresh, now);
            }
            return cache.find(day, code).map(QuoteCacheRepository.Entry::result)
                    .filter(result -> result.status() == QuoteResult.Status.FOUND && result.market() == market);
        }
    }

    private void refresh(Market market, MarketRefresh refresh, ZonedDateTime now) {
        try {
            var snapshot = official.fetch(market, now.toLocalDate());
            var completed = clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
            int attempts = (refresh.entry == null ? 0 : refresh.entry.postCloseAttempts())
                    + (QuoteRefreshPolicy.isPostClose(completed) ? 1 : 0);
            var next = QuoteRefreshPolicy.nextAttemptAt(completed, false);
            transactions.executeWithoutResult(status -> cache.saveBatch(completed, snapshot.quotes()));
            refresh.entry = new QuoteCacheRepository.Entry(
                    QuoteResult.found(null, market, null, snapshot.priceDate()), 0, attempts, next, completed.toInstant());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            rememberFailure(market, refresh);
        } catch (IOException exception) {
            LOGGER.warn("Official batch unavailable: market={}, reason={}", market, exception.getClass().getSimpleName());
            rememberFailure(market, refresh);
        }
    }

    private void rememberFailure(Market market, MarketRefresh refresh) {
        var now = clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
        int failures = refresh.entry == null ? 1 : refresh.entry.failureCount() + 1;
        int attempts = (refresh.entry == null ? 0 : refresh.entry.postCloseAttempts())
                + (QuoteRefreshPolicy.isPostClose(now) ? 1 : 0);
        var next = QuoteRefreshPolicy.nextAttemptAt(now, true);
        transactions.executeWithoutResult(status -> cache.markBatchFailure(now, market));
        refresh.entry = new QuoteCacheRepository.Entry(QuoteResult.unavailable(), failures, attempts, next, now.toInstant());
    }

    private static final class MarketRefresh {
        private LocalDate day;
        private QuoteCacheRepository.Entry entry;
    }
}
