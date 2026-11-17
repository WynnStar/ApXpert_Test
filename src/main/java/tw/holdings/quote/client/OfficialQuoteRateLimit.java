package tw.holdings.quote.client;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tw.holdings.quote.Market;

@Component
class OfficialQuoteRateLimit {
    private static final Duration FAILURE_PAUSE = Duration.ofMinutes(30);
    private final Map<Market, SourceGate> gates = new EnumMap<>(Market.class);
    private final LongSupplier nanoTime;
    private final LongSupplier delayNanos;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    @Autowired
    OfficialQuoteRateLimit(@Value("${quotes.min-request-interval:PT30S}") Duration minimum,
                           @Value("${quotes.max-request-interval:PT60S}") Duration maximum) {
        this(minimum, maximum, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    OfficialQuoteRateLimit(Duration minimum, Duration maximum, LongSupplier nanoTime, Sleeper sleeper) {
        if (minimum.isNegative() || maximum.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("Invalid official request interval range");
        }
        long minimumNanos = minimum.toNanos();
        long maximumNanos = maximum.toNanos();
        this.delayNanos = minimumNanos == maximumNanos ? () -> minimumNanos
                : () -> ThreadLocalRandom.current().nextLong(minimumNanos, maximumNanos);
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        for (Market market : Market.values()) {
            gates.put(market, new SourceGate());
        }
    }

    SourceGate gate(Market market) {
        return gates.get(market);
    }

    final class SourceGate {
        private long completedAtNanos;
        private long delayAfterCompletion;
        private boolean requested;
        private Instant pausedUntil = Instant.MIN;

        void beforeRequest(Instant now) throws IOException, InterruptedException {
            if (now.isBefore(pausedUntil)) {
                throw new IOException("Official source is cooling down");
            }
            if (requested) {
                long remaining = delayAfterCompletion - (nanoTime.getAsLong() - completedAtNanos);
                if (remaining > 0) {
                    sleeper.sleep(remaining);
                }
            }
        }

        void afterRequest() {
            completedAtNanos = nanoTime.getAsLong();
            delayAfterCompletion = delayNanos.getAsLong();
            requested = true;
        }

        void pause(Instant now, String retryAfter) {
            Instant minimumEnd = now.plus(FAILURE_PAUSE);
            Instant headerEnd = retryAfterEnd(now, retryAfter);
            Instant end = headerEnd.isAfter(minimumEnd) ? headerEnd : minimumEnd;
            if (end.isAfter(pausedUntil)) {
                pausedUntil = end;
            }
        }

        private Instant retryAfterEnd(Instant now, String text) {
            if (!text.isBlank()) {
                try {
                    return now.plusSeconds(Long.parseLong(text.strip()));
                } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException invalidSeconds) {
                    try {
                        return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    } catch (DateTimeParseException malformed) {
                        // 無效或缺少的標頭使用來源冷卻下限。
                    }
                }
            }
            return now;
        }
    }
}
