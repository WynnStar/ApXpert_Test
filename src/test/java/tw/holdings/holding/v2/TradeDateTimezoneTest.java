package tw.holdings.holding.v2;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import tw.holdings.holding.error.HoldingValidationException;
import tw.holdings.quote.marketstatus.MarketStatusService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TradeDateTimezoneTest {
    @Test
    void taipeiMidnightChangesTodayWhileUtcIsStillThePreviousDay() {
        assertEquals(LocalDate.parse("2026-11-17"), rules("2026-11-17T15:59:59Z").today());
        assertEquals(LocalDate.parse("2026-11-18"), rules("2026-11-17T16:00:00Z").today());
    }

    @Test
    void newAndEditedTradesAcceptTaipeiTodayAndRejectTaipeiTomorrow() {
        var rules = rules("2026-11-17T16:00:00Z");
        var today = LocalDate.parse("2026-11-18");
        assertDoesNotThrow(() -> rules.validateNew("2330", today, rules.today()));
        assertDoesNotThrow(() -> rules.validateEdit("2330", today.minusDays(1), today));
        assertThrows(HoldingValidationException.class,
                () -> rules.validateNew("2330", today.plusDays(1), rules.today()));
        assertThrows(HoldingValidationException.class,
                () -> rules.validateEdit("2330", today, today.plusDays(1)));
    }

    @Test
    void fiveYearFloorAndDateLockAdvanceAtTaipeiMidnight() {
        var before = rules("2026-11-17T15:59:59Z");
        var after = rules("2026-11-17T16:00:00Z");
        var oldDate = LocalDate.parse("2021-11-17");

        assertEquals(oldDate, before.range("2330").minDate());
        assertFalse(before.editDateLocked(oldDate, before.today()));
        assertEquals(LocalDate.parse("2021-11-18"), after.range("2330").minDate());
        assertEquals(LocalDate.parse("2026-11-18"), after.range("2330").maxDate());
        assertTrue(after.editDateLocked(oldDate, after.today()));
        assertThrows(HoldingValidationException.class,
                () -> after.validateNew("2330", oldDate, after.today()));
        assertDoesNotThrow(() -> after.validateEdit("2330", oldDate, oldDate));
    }

    private static TradeDateRules rules(String instant) {
        // 刻意注入 UTC Clock，確保交易日期仍由業務規則轉成台北日期。
        return new TradeDateRules(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                mock(MarketStatusService.class));
    }
}
