package tw.holdings.quote;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class QuoteRefreshPolicy {
    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime FIRST_REFRESH = LocalTime.of(13, 35);
    private static final LocalTime SECOND_REFRESH = LocalTime.of(15, 5);
    private static final Duration FAILURE_PAUSE = Duration.ofMinutes(30);

    private QuoteRefreshPolicy() {
    }

    static boolean isWeekday(ZonedDateTime now) {
        return now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static int refreshSlot(ZonedDateTime now) {
        if (!isWeekday(now) || now.toLocalTime().isBefore(FIRST_REFRESH)) {
            return 0;
        }
        return now.toLocalTime().isBefore(SECOND_REFRESH) ? 1 : 2;
    }

    static boolean isPostClose(ZonedDateTime now) {
        return refreshSlot(now) > 0;
    }

    static Instant slotStart(ZonedDateTime now) {
        LocalTime time = refreshSlot(now) == 2 ? SECOND_REFRESH : FIRST_REFRESH;
        return now.toLocalDate().atTime(time).atZone(TAIPEI).toInstant();
    }

    static boolean canRefresh(ZonedDateTime now, QuoteCacheRepository.Entry entry) {
        if (entry == null) {
            return true;
        }
        if (entry.result().status() == QuoteResult.Status.NOT_FOUND
                || now.toLocalDate().equals(entry.result().priceDate())) {
            return false;
        }
        if (entry.nextAttemptAt() != null && now.toInstant().isBefore(entry.nextAttemptAt())) {
            return false;
        }
        if (isPostClose(now)) {
            return entry.lastAttemptAt() == null || entry.lastAttemptAt().isBefore(slotStart(now));
        }
        return entry.result().status() == QuoteResult.Status.UNAVAILABLE || entry.failureCount() > 0;
    }

    static Instant nextAttemptAt(ZonedDateTime now, boolean failed) {
        LocalDate date = now.toLocalDate();
        LocalTime nextTime = FIRST_REFRESH;
        int slot = refreshSlot(now);
        if (slot == 1) {
            nextTime = SECOND_REFRESH;
        } else if (slot == 2 || !isWeekday(now)) {
            date = date.plusDays(1);
            while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
            }
        }
        Instant scheduled = date.atTime(nextTime).atZone(TAIPEI).toInstant();
        if (failed && slot == 0) {
            return now.toInstant().plus(FAILURE_PAUSE);
        }
        Instant cooldownEnd = failed ? now.toInstant().plus(FAILURE_PAUSE) : now.toInstant();
        return scheduled.isBefore(cooldownEnd) ? cooldownEnd : scheduled;
    }
}
