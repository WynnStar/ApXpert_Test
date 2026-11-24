package tw.holdings.quote;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 要求已結束的一般交易日；完整官方日曆在下一批接入。 */
@Component
class QuoteTargetDates {
    LocalDate latestExpected(Market market,ZonedDateTime now) {
        var day=now.toLocalDate();
        if (now.toLocalTime().isBefore(LocalTime.of(13,40))) day=day.minusDays(1);
        return previousWeekday(day);
    }
    List<LocalDate> candidates(Market market,ZonedDateTime now,int maximum) {
        List<LocalDate> dates=new ArrayList<>();
        var day=latestExpected(market,now);
        for (int i=0;i<maximum;i++) { dates.add(day);day=previousWeekday(day.minusDays(1)); }
        return dates;
    }
    private LocalDate previousWeekday(LocalDate day) {
        while (day.getDayOfWeek()==java.time.DayOfWeek.SATURDAY || day.getDayOfWeek()==java.time.DayOfWeek.SUNDAY) day=day.minusDays(1);
        return day;
    }
}
