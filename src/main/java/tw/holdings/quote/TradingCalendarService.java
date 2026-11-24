package tw.holdings.quote;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class TradingCalendarService {
    enum Status { OPEN,CLOSED,UNKNOWN }
    private final TradingCalendarRepository repository;
    private final Set<LocalDate> closed,opened;
    private final Set<Market> loading=ConcurrentHashMap.newKeySet();
    TradingCalendarService(TradingCalendarRepository repository,
        @Value("${quotes.calendar-closed-dates:}") String closed,@Value("${quotes.calendar-open-dates:}") String opened) {
        this.repository=repository;this.closed=parse(closed);this.opened=parse(opened);
        if(this.closed.stream().anyMatch(this.opened::contains))throw new IllegalArgumentException("Conflicting calendar overrides");
    }
    private static Set<LocalDate> parse(String value) {
        if(value.isBlank())return Set.of();
        return Arrays.stream(value.split(",")).map(String::strip).map(LocalDate::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    Status status(Market market,LocalDate day) {
        if(closed.contains(day))return Status.CLOSED;
        if(opened.contains(day))return Status.OPEN;
        var official=repository.open(market,day);
        if(official.isPresent())return official.get()?Status.OPEN:Status.CLOSED;
        return day.getDayOfWeek()==DayOfWeek.SATURDAY || day.getDayOfWeek()==DayOfWeek.SUNDAY?Status.CLOSED:Status.UNKNOWN;
    }
    void begin(Market market) { loading.add(market); }
    void end(Market market) { loading.remove(market); }
    boolean loading(Market market) { return loading.contains(market); }
}
