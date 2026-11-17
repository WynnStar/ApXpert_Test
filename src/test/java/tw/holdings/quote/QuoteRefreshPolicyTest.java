package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteRefreshPolicyTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 17);

    @Test
    void slotsStartAtTaipei1335And1505OnlyOnWeekdays() {
        assertEquals(0, QuoteRefreshPolicy.refreshSlot(at(13, 34)));
        assertEquals(1, QuoteRefreshPolicy.refreshSlot(at(13, 35)));
        assertEquals(1, QuoteRefreshPolicy.refreshSlot(at(15, 4)));
        assertEquals(2, QuoteRefreshPolicy.refreshSlot(at(15, 5)));
        assertEquals(0, QuoteRefreshPolicy.refreshSlot(at(15, 5).plusDays(4)));
        assertEquals(0, QuoteRefreshPolicy.refreshSlot(at(15, 5).plusDays(5)));
    }

    @Test
    void aLateFirstAttemptConsumesOnlyTheLatestSlotWithoutCatchupBurst() {
        var late = at(15, 20);
        var result = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("100.00"), DAY.minusDays(1));
        var entry = new QuoteCacheRepository.Entry(result, 0, 1,
                QuoteRefreshPolicy.nextAttemptAt(late, false), late.toInstant());

        assertFalse(QuoteRefreshPolicy.canRefresh(at(15, 21), entry));
        assertFalse(QuoteRefreshPolicy.canRefresh(at(23, 59), entry));
        assertEquals(2, QuoteRefreshPolicy.refreshSlot(late));
    }

    @Test
    void firstSlotWaitsForSecondSlotAndTodaysPriceStopsBoth() {
        var result = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("100.00"), DAY.minusDays(1));
        var entry = new QuoteCacheRepository.Entry(result, 0, 1,
                QuoteRefreshPolicy.nextAttemptAt(at(13, 35), false), at(13, 35).toInstant());

        assertFalse(QuoteRefreshPolicy.canRefresh(at(14, 5), entry));
        assertFalse(QuoteRefreshPolicy.canRefresh(at(15, 4), entry));
        assertTrue(QuoteRefreshPolicy.canRefresh(at(15, 5), entry));

        var today = new QuoteCacheRepository.Entry(
                QuoteResult.found("台積電", Market.TWSE, new BigDecimal("110.00"), DAY),
                0, 1, entry.nextAttemptAt(), entry.lastAttemptAt());
        assertFalse(QuoteRefreshPolicy.canRefresh(at(15, 5), today));
    }

    @Test
    void failureWaitsAtLeastThirtyMinutesAndStillRespectsNextSlot() {
        assertEquals(at(10, 30).toInstant(), QuoteRefreshPolicy.nextAttemptAt(at(10, 0), true));
        assertEquals(at(15, 5).toInstant(), QuoteRefreshPolicy.nextAttemptAt(at(13, 35), true));
        assertEquals(at(15, 25).toInstant(), QuoteRefreshPolicy.nextAttemptAt(at(14, 55), true));
    }

    private static ZonedDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(QuoteRefreshPolicy.TAIPEI);
    }
}
