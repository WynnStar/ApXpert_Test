package tw.holdings.quote;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 啟動與日常缺價補齊，共用市場進度；不依賴清單已經有持股。 */
@Component
@ConditionalOnProperty(name="quotes.warmup-enabled",matchIfMissing=true)
class QuoteWarmup {
    private static final Logger LOGGER=LoggerFactory.getLogger(QuoteWarmup.class);
    private final BatchQuoteService batches;
    private final QuoteCacheRepository cache;
    private final QuoteTargetDates dates;
    private final Clock clock;
    private final ExecutorService workers;
    private final int lookback;
    private final Set<Market> pending=ConcurrentHashMap.newKeySet();
    QuoteWarmup(BatchQuoteService batches,QuoteCacheRepository cache,QuoteTargetDates dates,Clock clock,
                @Qualifier("quoteWarmupWorkers") ExecutorService workers,
                @Value("${quotes.warmup-lookback:14}") int lookback) {
        if (lookback<1 || lookback>31) throw new IllegalArgumentException("Warmup lookback must be 1..31 sessions");
        this.batches=batches;this.cache=cache;this.dates=dates;this.clock=clock;this.workers=workers;this.lookback=lookback;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${quotes.refresh-check-ms:5000}")
    void tick() {
        var now=clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
        for (Market market:Market.values()) {
            if (!dates.ready(market) || cache.hasMarketPrice(market,dates.latestExpected(market,now)) || !pending.add(market)) continue;
            try {
                workers.execute(()->{
                    try { fill(market); }
                    catch (RuntimeException error) { LOGGER.error("Quote warmup failed for {}",market,error); }
                    finally { pending.remove(market); }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) { pending.remove(market); }
        }
    }
    private void fill(Market market) {
        var now=clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
        for (var target:dates.candidates(market,now,lookback)) {
            if (cache.hasMarketPrice(market,target)) return;
            var outcome=batches.warm(market,target);
            if (outcome!=BatchQuoteService.Outcome.NOT_PUBLISHED || Thread.currentThread().isInterrupted()) return;
        }
    }
}
