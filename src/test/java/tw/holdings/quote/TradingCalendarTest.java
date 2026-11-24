package tw.holdings.quote;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tw.holdings.quote.client.OfficialCalendarClient;
import tw.holdings.quote.client.OfficialBatchQuoteClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@JdbcTest
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TradingCalendarTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    TradingCalendarRepository repository;
    TradingCalendarService calendar;
    QuoteTargetDates dates;
    ExecutorService workers;
    static final LocalDate FRIDAY=LocalDate.of(2026,11,20);
    static final LocalDate THURSDAY=FRIDAY.minusDays(1);
    Clock clock;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM quote_market_calendar");jdbc.update("DELETE FROM quote_cache");jdbc.update("DELETE FROM quote_market_refresh");
        repository=new TradingCalendarRepository(jdbc);calendar=new TradingCalendarService(repository,"","");dates=new QuoteTargetDates(calendar);
        clock=Clock.fixed(FRIDAY.plusDays(1).atTime(10,0).atZone(QuoteRefreshPolicy.TAIPEI).toInstant(),ZoneOffset.UTC);
        workers=mock(ExecutorService.class);doAnswer(c->{c.<Runnable>getArgument(0).run();return null;}).when(workers).execute(any(Runnable.class));
    }
    OfficialCalendarClient.Snapshot annual(int year,LocalDate holiday) {
        return new OfficialCalendarClient.Snapshot(year,Map.of(holiday,new OfficialCalendarClient.Day(false,"官方休市")),"https://official.example/calendar");
    }
    void fridayClosed() { for(var m:Market.values())repository.save(m,annual(2026,FRIDAY),clock.instant()); }
    BatchQuoteService batches(OfficialBatchQuoteClient official) {
        return new BatchQuoteService(official,new QuoteCacheRepository(jdbc),clock,new TransactionTemplate(tm),new QuoteMarketRefreshRepository(jdbc),new MarketRefreshPolicy(b->0),dates);
    }
    @Test void persistsWholeYearAndRestartKeepsKnownFridayHoliday() {
        fridayClosed();assertEquals(730,jdbc.queryForObject("SELECT COUNT(*) FROM quote_market_calendar",Integer.class));
        var restarted=new QuoteTargetDates(new TradingCalendarService(new TradingCalendarRepository(jdbc),"",""));
        assertEquals(THURSDAY,restarted.latestExpected(Market.TWSE,clock.instant().atZone(QuoteRefreshPolicy.TAIPEI)));
        assertEquals(TradingCalendarService.Status.OPEN,calendar.status(Market.TPEX,THURSDAY));
    }
    @Test void saturdayWarmupWithHolidayFridayRequestsThursdayOnly() throws Exception {
        fridayClosed();var official=mock(OfficialBatchQuoteClient.class);
        for(var m:Market.values())when(official.fetch(m,THURSDAY)).thenReturn(new OfficialBatchQuoteClient.Snapshot(THURSDAY,Map.of(m==Market.TWSE?"2330":"6488",QuoteResult.found("公司",m,new BigDecimal("100"),THURSDAY))));
        var warm=new QuoteWarmup(batches(official),new QuoteCacheRepository(jdbc),dates,clock,workers,14);
        warm.tick();warm.tick();
        for(var m:Market.values()) { verify(official,times(1)).fetch(m,THURSDAY);verify(official,never()).fetch(m,FRIDAY); }
    }
    @Test void saturdayWithSufficientThursdayCacheDoesNotPollAgain() {
        fridayClosed();var cache=new QuoteCacheRepository(jdbc);
        for(var m:Market.values())cache.saveBatch(THURSDAY.atTime(16,0).atZone(QuoteRefreshPolicy.TAIPEI),Map.of(m==Market.TWSE?"2330":"6488",QuoteResult.found("公司",m,new BigDecimal("100"),THURSDAY)));
        var official=mock(OfficialBatchQuoteClient.class);
        new QuoteWarmup(batches(official),cache,dates,clock,workers,14).tick();verifyNoInteractions(official);
    }
    @Test void unknownWeekdayRemainsCandidateAndDoesNotBecomeAConfirmedHoliday() {
        assertEquals(TradingCalendarService.Status.UNKNOWN,calendar.status(Market.TWSE,FRIDAY));
        assertEquals(FRIDAY,dates.latestExpected(Market.TWSE,clock.instant().atZone(QuoteRefreshPolicy.TAIPEI)));
    }
    @Test void explicitEmergencyClosureAndExceptionalOpenSaturdayOverrideAnnualCalendar() {
        fridayClosed();var overrides=new TradingCalendarService(repository,THURSDAY.toString(),FRIDAY.plusDays(1).toString());
        assertEquals(TradingCalendarService.Status.CLOSED,overrides.status(Market.TWSE,THURSDAY));
        assertEquals(TradingCalendarService.Status.OPEN,overrides.status(Market.TPEX,FRIDAY.plusDays(1)));
        assertThrows(IllegalArgumentException.class,()->new TradingCalendarService(repository,"2026-11-20","2026-11-20"));
    }
    @Test void calendarLoadingDefersWarmupUntilItCompletes() {
        for(var m:Market.values())calendar.begin(m);
        var official=mock(OfficialBatchQuoteClient.class);
        new QuoteWarmup(batches(official),new QuoteCacheRepository(jdbc),dates,clock,workers,14).tick();
        verifyNoInteractions(workers);verifyNoInteractions(official);
        for(var m:Market.values())calendar.end(m);
        assertTrue(dates.ready(Market.TWSE));
    }
    @Test void failedRefreshRetainsPersistedCalendarAndDoesNotRetryOnEveryTick() throws Exception {
        fridayClosed();var official=mock(OfficialCalendarClient.class);
        Clock next=Clock.offset(clock,Duration.ofDays(2));
        for(var m:Market.values())when(official.fetch(m,2026)).thenThrow(new IOException("offline"));
        var refresh=new CalendarRefresh(official,repository,calendar,next,workers);
        refresh.tick();refresh.tick();
        for(var m:Market.values())verify(official,times(1)).fetch(m,2026);
        assertEquals(TradingCalendarService.Status.CLOSED,calendar.status(Market.TWSE,FRIDAY));
        assertFalse(calendar.loading(Market.TWSE));
    }
    @Test void freshPersistedCalendarSurvivesRestartWithoutRefetch() {
        fridayClosed();var official=mock(OfficialCalendarClient.class);
        new CalendarRefresh(official,repository,calendar,clock,workers).tick();verifyNoInteractions(official);
    }
    @Test void taipeiNewYearWithUtcHostUsesLastYearsFinalSession() {
        var day=LocalDate.of(2027,1,1);
        repository.save(Market.TWSE,annual(2027,day),clock.instant());
        var utc=Instant.parse("2026-12-31T16:01:00Z").atZone(QuoteRefreshPolicy.TAIPEI);
        assertEquals(LocalDate.of(2026,12,31),dates.latestExpected(Market.TWSE,utc));
        assertEquals(LocalDate.of(2026,12,31),dates.latestExpected(Market.TWSE,day.atTime(15,0).atZone(QuoteRefreshPolicy.TAIPEI)));
    }
    @Test void invalidSnapshotCannotDeleteExistingCalendar() {
        fridayClosed();
        assertThrows(IllegalArgumentException.class,()->repository.save(Market.TWSE,annual(2026,LocalDate.of(2027,1,1)),clock.instant()));
        assertEquals(TradingCalendarService.Status.CLOSED,calendar.status(Market.TWSE,FRIDAY));
    }
}
