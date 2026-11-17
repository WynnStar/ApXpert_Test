package tw.holdings.quote.client;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tw.holdings.quote.Market;

@Component
class OfficialQuoteRateLimit {
    private static final Duration DEFAULT_PAUSE = Duration.ofMinutes(5);
    private final Map<Market, SourceGate> gates = new EnumMap<>(Market.class);
    private final long intervalNanos;

    OfficialQuoteRateLimit(@Value("${quotes.min-request-interval:PT2S}") Duration interval) {
        intervalNanos = interval.toNanos();
        for (Market market : Market.values()) {
            gates.put(market, new SourceGate());
        }
    }

    SourceGate gate(Market market) {
        return gates.get(market);
    }

    final class SourceGate {
        private long lastRequestNanos;
        private boolean requested;
        private Instant pausedUntil = Instant.MIN;

        void beforeRequest(Instant now) throws IOException, InterruptedException {
            if (now.isBefore(pausedUntil)) {
                throw new IOException("Official source is rate-limited");
            }
            if (requested) {
                long remaining = intervalNanos - (System.nanoTime() - lastRequestNanos);
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                }
            }
            lastRequestNanos = System.nanoTime();
            requested = true;
        }

        void pause(Instant now, String retryAfter) {
            if (!retryAfter.isBlank()) {
                try {
                    pausedUntil = now.plusSeconds(Long.parseLong(retryAfter.strip()));
                    return;
                } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException ignored) {
                    try {
                        pausedUntil = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                        return;
                    } catch (DateTimeParseException malformed) {
                        // 來源未提供有效的標頭，採用預設冷卻時間。
                    }
                }
            }
            pausedUntil = now.plus(DEFAULT_PAUSE);
        }
    }
}
