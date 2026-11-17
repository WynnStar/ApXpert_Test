package tw.holdings.quote.marketstatus;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "quotes.market-status-sync", matchIfMissing = true)
class MarketStatusBatch {
    private static final LocalTime RUN_AT = LocalTime.of(8, 0);

    private final MarketStatusSyncService synchronization;
    private final Clock clock;
    private final ExecutorService quoteWorkers;

    MarketStatusBatch(MarketStatusSyncService synchronization, Clock clock, ExecutorService quoteWorkers) {
        this.synchronization = synchronization;
        this.clock = clock;
        this.quoteWorkers = quoteWorkers;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    void enqueue() {
        if (!now().toLocalTime().isBefore(RUN_AT)) {
            quoteWorkers.execute(this::runIfDue);
        }
    }

    private synchronized void runIfDue() {
        var now = now();
        if (!now.toLocalTime().isBefore(RUN_AT)) {
            synchronization.sync(now.toLocalDate());
        }
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
