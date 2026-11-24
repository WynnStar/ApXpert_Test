package tw.holdings.quote;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tw.holdings.quote.client.OfficialCalendarClient;

@Repository
class TradingCalendarRepository {
    private final JdbcTemplate jdbc;
    TradingCalendarRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    Optional<Boolean> open(Market market,LocalDate day) {
        return jdbc.query("SELECT is_open FROM quote_market_calendar WHERE market=? AND calendar_date=?",
            (r,n)->r.getBoolean(1),market.name(),day).stream().findFirst();
    }
    Optional<Instant> checked(Market market,int year) {
        return jdbc.query("SELECT MIN(checked_at) FROM quote_market_calendar WHERE market=? AND calendar_date>=? AND calendar_date<?",
            (r,n)->{var value=r.getObject(1,OffsetDateTime.class);return value==null?null:value.toInstant();},
            market.name(),LocalDate.of(year,1,1),LocalDate.of(year+1,1,1)).stream().filter(Objects::nonNull).findFirst();
    }
    @Transactional
    public void save(Market market,OfficialCalendarClient.Snapshot snapshot,Instant checked) {
        int year=snapshot.year();
        if(snapshot.days().isEmpty() || snapshot.days().keySet().stream().anyMatch(d->d.getYear()!=year))throw new IllegalArgumentException("Invalid calendar year");
        jdbc.update("DELETE FROM quote_market_calendar WHERE market=? AND calendar_date>=? AND calendar_date<?",market.name(),LocalDate.of(year,1,1),LocalDate.of(year+1,1,1));
        List<Object[]> rows=new ArrayList<>();
        for(var day=LocalDate.of(year,1,1);day.getYear()==year;day=day.plusDays(1)) {
            boolean weekday=day.getDayOfWeek()!=DayOfWeek.SATURDAY && day.getDayOfWeek()!=DayOfWeek.SUNDAY;
            var entry=snapshot.days().get(day);
            rows.add(new Object[]{market.name(),day,entry==null?weekday:entry.open(),entry==null?(weekday?"一般交易日":"週末"):entry.reason(),snapshot.source(),checked.atOffset(ZoneOffset.UTC)});
        }
        jdbc.batchUpdate("INSERT INTO quote_market_calendar(market,calendar_date,is_open,reason,source,checked_at) VALUES(?,?,?,?,?,?)",rows);
    }
}
