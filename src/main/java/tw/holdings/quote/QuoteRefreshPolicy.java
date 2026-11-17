package tw.holdings.quote;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;


final class QuoteRefreshPolicy {
    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    static final int MAX_POST_CLOSE_ATTEMPTS = 4;
    private static final LocalTime CLOSE = LocalTime.of(13, 30);

    private QuoteRefreshPolicy() {
    }

    static boolean isWeekday(ZonedDateTime now) {
        return now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static boolean isPostClose(ZonedDateTime now) {
        return isWeekday(now) && !now.toLocalTime().isBefore(CLOSE);
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
            return entry.postCloseAttempts() < MAX_POST_CLOSE_ATTEMPTS;
        }
        return entry.result().status() == QuoteResult.Status.UNAVAILABLE || entry.failureCount() > 0;
    }

    static Duration retryDelay(boolean postClose, int failures) {
        if (postClose || failures >= 4) {
            return Duration.ofMinutes(5);
        }
        return failures == 0 ? Duration.ZERO : Duration.ofSeconds(30L << (failures - 1));
    }
}
