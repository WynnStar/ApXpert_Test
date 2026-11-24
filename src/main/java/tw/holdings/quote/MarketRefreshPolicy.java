package tw.holdings.quote;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 市場批次的觀察窗口；首次偏移和恢復窗口由狀態表保存。 */
@Component
class MarketRefreshPolicy {
    private final LocalTime start;
    private final LocalTime end;
    private final Duration regular;
    private final Duration lowFrequency;
    private final Duration recovery;
    private final IntUnaryOperator random;

    @Autowired
    MarketRefreshPolicy(@Value("${quotes.refresh-start:13:40}") LocalTime start,
                        @Value("${quotes.refresh-end:14:30}") LocalTime end,
                        @Value("${quotes.refresh-interval:PT2M}") Duration regular,
                        @Value("${quotes.refresh-low-frequency:PT10M}") Duration lowFrequency,
                        @Value("${quotes.refresh-recovery-window:PT30M}") Duration recovery) {
        this(start, end, regular, lowFrequency, recovery, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    MarketRefreshPolicy(IntUnaryOperator random) {
        this(LocalTime.of(13,40), LocalTime.of(14,30), Duration.ofMinutes(2),
                Duration.ofMinutes(10), Duration.ofMinutes(30), random);
    }

    private MarketRefreshPolicy(LocalTime start, LocalTime end, Duration regular,
                                Duration lowFrequency, Duration recovery, IntUnaryOperator random) {
        if (!start.isBefore(end) || regular.isNegative() || regular.isZero()
                || lowFrequency.isNegative() || lowFrequency.isZero()
                || recovery.isNegative() || recovery.isZero()) {
            throw new IllegalArgumentException("Invalid quote refresh window or interval");
        }
        this.start=start; this.end=end; this.regular=regular;
        this.lowFrequency=lowFrequency; this.recovery=recovery; this.random=random;
    }

    Instant firstAttempt(LocalDate target) {
        return target.atTime(start).atZone(QuoteRefreshPolicy.TAIPEI).plusSeconds(15+random.applyAsInt(16)).toInstant();
    }

    Instant normalEnd(LocalDate target) {
        return target.atTime(end).atZone(QuoteRefreshPolicy.TAIPEI).toInstant();
    }

    boolean normalWindow(Instant now, QuoteMarketRefreshRepository.State state) {
        return !now.isBefore(state.windowStart()) && now.isBefore(normalEnd(state.target()));
    }

    Instant next(ZonedDateTime now, QuoteMarketRefreshRepository.State state, boolean transportFailure,
                 Instant sourceAvailableAt) {
        Instant instant=now.toInstant();
        Instant next;
        if (transportFailure) {
            next=instant.plus(Duration.ofMinutes(30));
        } else if (instant.isBefore(state.windowStart())) {
            next=state.windowStart();
        } else {
            boolean recovering=state.recoveryStarted()!=null
                    && instant.isBefore(state.recoveryStarted().plus(recovery));
            next=instant.plus(normalWindow(instant,state)||recovering ? regular : lowFrequency)
                    .plusSeconds(random.applyAsInt(11));
        }
        return sourceAvailableAt!=null && sourceAvailableAt.isAfter(next) ? sourceAvailableAt : next;
    }
}
