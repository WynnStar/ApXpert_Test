package tw.holdings.quote;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 已公布時段與官方開休市日曆共同決定目標；未知日期不當成休市。 */
@Component
class QuoteTargetDates {
    private final TradingCalendarService calendar;
    QuoteTargetDates(TradingCalendarService calendar) { this.calendar=calendar; }
    boolean ready(Market market) { return !calendar.loading(market); }
    boolean closed(Market market,LocalDate day) { return calendar.status(market,day)==TradingCalendarService.Status.CLOSED; }
    LocalDate latestExpected(Market market,ZonedDateTime now) {
        var day=now.toLocalDate();
        if (now.toLocalTime().isBefore(LocalTime.of(13,40))) day=day.minusDays(1);
        return previousSession(market,day);
    }
    List<LocalDate> candidates(Market market,ZonedDateTime now,int maximum) {
        List<LocalDate> dates=new ArrayList<>();
        var day=latestExpected(market,now);
        for (int i=0;i<maximum;i++) { dates.add(day);day=previousSession(market,day.minusDays(1)); }
        return dates;
    }
    private LocalDate previousSession(Market market,LocalDate day) {
        int checked=0;
        while (closed(market,day)) {
            if(++checked>366)throw new IllegalStateException("Calendar contains no session in one year");
            day=day.minusDays(1);
        }
        return day;
    }
}
