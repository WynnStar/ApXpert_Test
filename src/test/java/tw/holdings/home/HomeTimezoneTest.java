package tw.holdings.home;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.*;

class HomeTimezoneTest {
    @Test
    void homepageUsesTaipeiOffsetAndMatchingDisplayTimeRegardlessOfHostTimezone() {
        var before = ZonedDateTime.now(ZoneId.of("Asia/Taipei")).toInstant();
        var model = new ExtendedModelMap();

        assertEquals("home", new HomeController().home(model));

        var after = ZonedDateTime.now(ZoneId.of("Asia/Taipei")).toInstant();
        var actual = OffsetDateTime.parse((String) model.get("dateTime"));
        assertEquals(ZoneOffset.ofHours(8), actual.getOffset());
        assertFalse(actual.toInstant().isBefore(before));
        assertFalse(actual.toInstant().isAfter(after));
        assertEquals(actual.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")),
                model.get("currentTime"));
    }
}
