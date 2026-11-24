package tw.holdings.quote;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tw.holdings.quote.client.OfficialCalendarClient;

@Component
@ConditionalOnProperty(name="quotes.calendar-sync-enabled",matchIfMissing=true)
class CalendarRefresh {
    private static final Logger LOGGER=LoggerFactory.getLogger(CalendarRefresh.class);
    private final OfficialCalendarClient official;
    private final TradingCalendarRepository repository;
    private final TradingCalendarService calendar;
    private final Clock clock;
    private final ExecutorService workers;
    private final Set<Market> pending=ConcurrentHashMap.newKeySet();
    private final Map<String,Instant> retry=new ConcurrentHashMap<>();
    CalendarRefresh(OfficialCalendarClient official,TradingCalendarRepository repository,TradingCalendarService calendar,Clock clock,
                    @Qualifier("quoteCalendarWorkers") ExecutorService workers) {
        this.official=official;this.repository=repository;this.calendar=calendar;this.clock=clock;this.workers=workers;
        for(var market:Market.values())calendar.begin(market);
    }
    @Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${quotes.calendar-check-ms:3600000}")
    void tick() {
        for(var market:Market.values()) {
            if(!pending.add(market))continue;
            calendar.begin(market);
            try { workers.execute(()->{
                try {
                    var now=clock.instant().atZone(QuoteRefreshPolicy.TAIPEI);
                    refresh(market,now.getYear());
                    if(now.getMonthValue()==1)refresh(market,now.getYear()-1);
                } finally { calendar.end(market);pending.remove(market); }
            }); } catch(RejectedExecutionException busy) { calendar.end(market);pending.remove(market); }
        }
    }
    private void refresh(Market market,int year) {
        String key=market+":"+year;var now=clock.instant();
        if(now.isBefore(retry.getOrDefault(key,Instant.MIN)) || repository.checked(market,year).map(t->t.plus(Duration.ofDays(1)).isAfter(now)).orElse(false))return;
        retry.put(key,now.plus(Duration.ofHours(1)));
        try { repository.save(market,official.fetch(market,year),clock.instant()); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(Exception unavailable) { LOGGER.warn("Official calendar unavailable: market={}, year={}, reason={}",market,year,unavailable.getClass().getSimpleName()); }
    }
}
