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
    private static final LocalTime FIRST_REFRESH = LocalTime.of(13, 40);
    private static final LocalTime SECOND_REFRESH = LocalTime.of(14, 30);
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
            return true; // 市場層的持久化進度決定是否真的需要 HTTP 請求。
        }
        return entry.result().status() == QuoteResult.Status.UNAVAILABLE || entry.failureCount() > 0;
    }

    static Instant nextAttemptAt(ZonedDateTime now, boolean failed) {
        if (isPostClose(now)) {
            return now.toInstant().plus(refreshSlot(now)==1 ? Duration.ofMinutes(2) : Duration.ofMinutes(10));
        }
        if (failed) return now.toInstant().plus(FAILURE_PAUSE);
        LocalDate date=now.toLocalDate();
        if (!isWeekday(now)) {
            do { date=date.plusDays(1); }
            while (date.getDayOfWeek()==DayOfWeek.SATURDAY || date.getDayOfWeek()==DayOfWeek.SUNDAY);
        }
        return date.atTime(FIRST_REFRESH).atZone(TAIPEI).toInstant();
    }
}
