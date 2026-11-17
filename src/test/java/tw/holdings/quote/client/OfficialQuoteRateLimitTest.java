package tw.holdings.quote.client;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import tw.holdings.quote.Market;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialQuoteRateLimitTest {
    private static final Instant NOW = Instant.parse("2026-11-17T05:35:00Z");

    @Test
    void shorterRetryAfterCannotReduceThirtyMinuteMinimum() {
        var limiter = new OfficialQuoteRateLimit(Duration.ZERO, Duration.ZERO);
        var twse = limiter.gate(Market.TWSE);
        twse.pause(NOW, "90");
        assertThrows(IOException.class, () -> twse.beforeRequest(NOW.plusSeconds(1799)));
        assertDoesNotThrow(() -> twse.beforeRequest(NOW.plusSeconds(1800)));
        assertDoesNotThrow(() -> limiter.gate(Market.TPEX).beforeRequest(NOW));
    }

    @Test
    void longerRetryAfterAndHttpDateAreHonoredWithoutShorteningAnExistingPause() {
        var gate = new OfficialQuoteRateLimit(Duration.ZERO, Duration.ZERO).gate(Market.TWSE);
        gate.pause(NOW, "3600");
        gate.pause(NOW.plusSeconds(1), "");
        assertThrows(IOException.class, () -> gate.beforeRequest(NOW.plusSeconds(3599)));
        assertDoesNotThrow(() -> gate.beforeRequest(NOW.plusSeconds(3600)));

        gate.pause(NOW, "Tue, 17 Nov 2026 07:35:00 GMT");
        assertThrows(IOException.class, () -> gate.beforeRequest(NOW.plusSeconds(7199)));
        assertDoesNotThrow(() -> gate.beforeRequest(NOW.plusSeconds(7200)));
    }

    @Test
    void missingMalformedAndNegativeHeadersUseThirtyMinutes() {
        for (String header : new String[] {"", "bad", "-1"}) {
            var gate = new OfficialQuoteRateLimit(Duration.ZERO, Duration.ZERO).gate(Market.TWSE);
            gate.pause(NOW, header);
            assertThrows(IOException.class, () -> gate.beforeRequest(NOW.plusSeconds(1799)));
            assertDoesNotThrow(() -> gate.beforeRequest(NOW.plusSeconds(1800)));
        }
    }

    @Test
    void randomGapStartsAtRequestCompletionAndDoesNotBlockOtherMarket() throws Exception {
        AtomicLong elapsed = new AtomicLong();
        AtomicLong slept = new AtomicLong();
        var limiter = new OfficialQuoteRateLimit(Duration.ofSeconds(30), Duration.ofSeconds(60),
                elapsed::get, nanos -> { slept.set(nanos); elapsed.addAndGet(nanos); });
        var gate = limiter.gate(Market.TWSE);

        for (int index = 0; index < 20; index++) {
            gate.beforeRequest(NOW);
            if (index > 0) {
                // 已經經過 10 秒，剩餘的隨機等待應介於 20 至 50 秒。
                assertTrue(slept.get() >= Duration.ofSeconds(20).toNanos());
                assertTrue(slept.get() <= Duration.ofSeconds(50).toNanos());
            }
            gate.afterRequest();
            elapsed.addAndGet(Duration.ofSeconds(10).toNanos());
            slept.set(0);
            limiter.gate(Market.TPEX).beforeRequest(NOW);
            assertEquals(0, slept.get());
        }
    }

    @Test
    void invalidIntervalRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OfficialQuoteRateLimit(Duration.ofSeconds(60), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new OfficialQuoteRateLimit(Duration.ofSeconds(-1), Duration.ofSeconds(30)));
    }
}
