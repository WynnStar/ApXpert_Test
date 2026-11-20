package tw.holdings.quote.marketstatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketStatusTimezoneTest {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void scheduledCronRunsAtTaipeiEightWhichIsUtcMidnight() throws Exception {
        var scheduled = MarketStatusBatch.class.getDeclaredMethod("enqueue").getAnnotation(Scheduled.class);
        assertEquals("Asia/Taipei", scheduled.zone());
        var next = CronExpression.parse(scheduled.cron()).next(
                Instant.parse("2026-11-17T23:59:59Z").atZone(ZoneId.of(scheduled.zone())));
        assertNotNull(next);
        assertEquals(Instant.parse("2026-11-18T00:00:00Z"), next.toInstant());
        assertEquals(LocalDate.parse("2026-11-18"), next.toLocalDate());
    }

    @Test
    void startupBeforeTaipeiEightDoesNotQueueButEightDoes() {
        var sync = mock(MarketStatusSyncService.class);
        var workers = immediateWorkers();
        batch("2026-11-17T23:59:59Z", sync, workers).enqueue();
        verifyNoInteractions(sync, workers);

        batch("2026-11-18T00:00:00Z", sync, workers).enqueue();
        verify(sync).sync(LocalDate.parse("2026-11-18"));
    }

    @Test
    void lateStartupAfterTaipeiEightRunsWithoutWaitingForUtcEight() {
        var sync = mock(MarketStatusSyncService.class);
        batch("2026-11-17T01:00:00Z", sync, immediateWorkers()).enqueue();
        verify(sync).sync(LocalDate.parse("2026-11-17"));
    }

    private static MarketStatusBatch batch(String instant, MarketStatusSyncService sync,
                                           ExecutorService workers) {
        // 正式環境注入的 Clock 固定使用 Asia/Taipei；主機預設時區不影響它。
        return new MarketStatusBatch(sync, Clock.fixed(Instant.parse(instant), TAIPEI), workers);
    }

    private static ExecutorService immediateWorkers() {
        var workers = mock(ExecutorService.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(workers).execute(any(Runnable.class));
        return workers;
    }
}
