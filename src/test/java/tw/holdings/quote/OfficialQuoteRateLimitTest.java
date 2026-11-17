package tw.holdings.quote;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialQuoteRateLimitTest {
    private static final Instant NOW = Instant.parse("2026-11-17T05:30:00Z");

    @Test
    void honorsRetryAfterAndOnlyPausesTheAffectedMarket() {
        var limiter = new OfficialQuoteRateLimit(Duration.ZERO);
        var twse = limiter.gate(Market.TWSE);
        twse.pause(NOW, "90");
        assertThrows(IOException.class, () -> twse.beforeRequest(NOW.plusSeconds(89)));
        assertDoesNotThrow(() -> twse.beforeRequest(NOW.plusSeconds(90)));
        assertDoesNotThrow(() -> limiter.gate(Market.TPEX).beforeRequest(NOW));
    }

    @Test
    void usesFiveMinutePauseWhenHeaderIsMissing() {
        var gate = new OfficialQuoteRateLimit(Duration.ZERO).gate(Market.TWSE);
        gate.pause(NOW, "");
        assertThrows(IOException.class, () -> gate.beforeRequest(NOW.plusSeconds(299)));
        assertDoesNotThrow(() -> gate.beforeRequest(NOW.plusSeconds(300)));
    }

    @Test
    void spacesRequestsToTheSameMarket() throws Exception {
        var gate = new OfficialQuoteRateLimit(Duration.ofMillis(25)).gate(Market.TWSE);
        gate.beforeRequest(NOW);
        long start = System.nanoTime();
        gate.beforeRequest(NOW);
        assertTrue(System.nanoTime() - start >= Duration.ofMillis(20).toNanos());
    }
}
