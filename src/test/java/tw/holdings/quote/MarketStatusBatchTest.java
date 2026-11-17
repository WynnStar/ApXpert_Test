package tw.holdings.quote;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@JdbcTest
class MarketStatusBatchTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void runsOnceAfterEightAndRetainsStatusWhenOfficialFeedFails() throws Exception {
        var repository = new MarketStatusRepository(jdbc);
        var official = mock(OfficialMarketClient.class);
        var history = mock(DelistedQuoteHistory.class);
        var day = LocalDate.of(2026, 11, 17);
        var delisting = new MarketEvent("2888", Market.TWSE, day, MarketEvent.Kind.DELISTED, "新光金");
        when(official.delistings(eq(Market.TWSE), anyInt())).thenReturn(List.of(delisting));
        jdbc.update("INSERT INTO holdings(code, lots, buy_price) VALUES ('2888', 1, 10)");

        batch(repository, official, history, day, 7).enqueue();
        verifyNoInteractions(official);
        var ready = batch(repository, official, history, day, 9);
        ready.enqueue();
        ready.enqueue();
        verify(official, times(1)).delistings(Market.TWSE, 2026);
        verify(history, times(1)).refresh(delisting);

        when(official.delistings(eq(Market.TWSE), anyInt())).thenThrow(new IOException("offline"));
        batch(repository, official, history, day.plusDays(1), 8).enqueue();
        var status = new MarketStatusService(repository, clock(day.plusDays(1), 8));
        assertEquals("已下市", status.delisting("2888").orElseThrow().label());
    }

    @Test
    void importsOlderTpexDelistingsEvenIfTodaysRegularSyncAlreadyRan() throws Exception {
        var repository = new MarketStatusRepository(jdbc);
        var official = mock(OfficialMarketClient.class);
        var history = mock(DelistedQuoteHistory.class);
        var day = LocalDate.of(2026, 11, 17);
        var delisting = new MarketEvent("6250", Market.TPEX, LocalDate.of(2016, 5, 25),
                MarketEvent.Kind.DELISTED, "宇加");
        when(official.delistings(Market.TPEX, 2016)).thenReturn(List.of(delisting));
        jdbc.update("INSERT INTO holdings(code, lots, buy_price) VALUES ('6250', 1, 20)");
        assertTrue(repository.claimDay(day));

        var restart = batch(repository, official, history, day, 9);
        restart.enqueue();
        restart.enqueue();
        verify(official, times(1)).delistings(Market.TPEX, 2016);
        verify(history, times(1)).refresh(delisting);
        var status = new MarketStatusService(repository, clock(day, 9));
        assertEquals("已下櫃", status.delisting("6250").orElseThrow().label());
    }

    @Test
    void excludesOfficialTransfersFromDelistingsIncludingPreviouslyImportedYears() throws Exception {
        var repository = new MarketStatusRepository(jdbc);
        var official = mock(OfficialMarketClient.class);
        var history = mock(DelistedQuoteHistory.class);
        var day = LocalDate.of(2026, 11, 17);
        var previousEnd = new MarketEvent("1597", Market.TPEX, LocalDate.of(2020, 12, 23),
                MarketEvent.Kind.DELISTED, "直得");
        repository.markTpexYearImported(2020);
        repository.save(previousEnd);
        repository.saveFinalClose(previousEnd, new MarketStatusRepository.FinalClose(LocalDate.of(2020, 11, 1),
                LocalDate.of(2020, 12, 21), new BigDecimal("30.00")));
        var anotherEnd = new MarketEvent("1795", Market.TPEX, LocalDate.of(2019, 12, 16),
                MarketEvent.Kind.DELISTED, "美時");
        var actualEnd = new MarketEvent("4415", Market.TPEX, LocalDate.of(2019, 12, 16),
                MarketEvent.Kind.DELISTED, "台原藥");
        when(official.transfersToTwse(2020)).thenReturn(List.of(transferFrom(previousEnd)));
        when(official.transfersToTwse(2019)).thenReturn(List.of(transferFrom(anotherEnd)));
        when(official.delistings(Market.TPEX, 2019)).thenReturn(List.of(anotherEnd, actualEnd));
        assertTrue(repository.claimDay(day));

        var restart = batch(repository, official, history, day, 9);
        restart.enqueue();
        restart.enqueue();
        var status = new MarketStatusService(repository, clock(day, 9));
        assertTrue(status.delisting("1597").isEmpty());
        assertTrue(status.delisting("1795").isEmpty());
        assertTrue(status.buyRestriction("1597", previousEnd.date()).isEmpty());
        assertTrue(status.buyRestriction("1597", previousEnd.date().minusDays(1)).isEmpty());
        assertTrue(repository.finalClose(previousEnd).isEmpty());
        assertEquals(Market.TWSE, status.listingEvent("1597", day).orElseThrow().market());
        assertEquals(Market.TWSE, status.listingEvent("1795", day).orElseThrow().market());
        assertEquals("已下櫃", status.delisting("4415").orElseThrow().label());
        verify(official, times(1)).transfersToTwse(2020);
    }

    private MarketEvent transferFrom(MarketEvent delisting) {
        return new MarketEvent(delisting.code(), Market.TWSE, delisting.date(), MarketEvent.Kind.RELISTED,
                delisting.name());
    }

    @Test
    void requiresPreviouslyRecordedIssuerIdentityBeforeReactivatingAndKeepsTheGapBlocked() throws Exception {
        var repository = new MarketStatusRepository(jdbc);
        var official = mock(OfficialMarketClient.class);
        var history = mock(DelistedQuoteHistory.class);
        var day = LocalDate.of(2026, 11, 17);
        var oldDate = day.minusYears(3);
        var end = day.minusDays(10);
        for (String code : List.of("2888", "2809", "6806")) {
            repository.save(new MarketEvent(code, Market.TWSE, end, MarketEvent.Kind.DELISTED, "舊公司"));
        }
        repository.saveIdentity("2888", new MarketStatusRepository.Identity("12345678", oldDate));
        repository.saveIdentity("2809", new MarketStatusRepository.Identity("87654321", oldDate));
        when(official.listings(Market.TPEX)).thenReturn(List.of(
                new OfficialMarketClient.Listing("2888", Market.TPEX, day, "12345678", "重新掛牌"),
                new OfficialMarketClient.Listing("2809", Market.TPEX, day, "12345678", "代號重用"),
                new OfficialMarketClient.Listing("6806", Market.TPEX, day, "12345678", "尚無舊身分")));

        batch(repository, official, history, day, 8).enqueue();
        var status = new MarketStatusService(repository, clock(day, 8));
        assertTrue(status.delisting("2888").isEmpty());
        assertTrue(status.buyRestriction("2888", day).isEmpty());
        assertTrue(status.buyRestriction("2888", end).isPresent());
        assertTrue(status.delisting("2809").isPresent());
        assertTrue(status.delisting("6806").isPresent());
    }

    @Test
    void scansPastNoTradeMonthsAndStopsAfterSavingTheFinalPrice() throws Exception {
        var repository = new MarketStatusRepository(jdbc);
        var source = mock(QuoteSource.class);
        var cutoff = LocalDate.of(2026, 11, 15);
        var event = new MarketEvent("2888", Market.TWSE, cutoff.plusDays(1), MarketEvent.Kind.DELISTED, "新光金");
        var november = YearMonth.from(cutoff);
        var october = november.minusMonths(1);
        when(source.market()).thenReturn(Market.TWSE);
        when(source.fetch(event.code(), november, cutoff)).thenReturn(QuoteResult.notFound());
        when(source.fetch(event.code(), october, cutoff)).thenReturn(new QuoteResult(
                QuoteResult.Status.FOUND, event.name(), Market.TWSE, new BigDecimal("12.30"), october.atDay(28)));
        var history = new DelistedQuoteHistory(repository, List.of(source));

        history.refresh(event);
        history.refresh(event);
        var finalClose = repository.finalClose(event).orElseThrow();
        assertEquals(october.atDay(28), finalClose.priceDate());
        assertEquals(new BigDecimal("12.30"), finalClose.close());
        verify(source, times(1)).fetch(event.code(), november, cutoff);
        verify(source, times(1)).fetch(event.code(), october, cutoff);
    }

    @Test
    void reachesTheLastTradingMonthAfterSeveralEmptyMonths() throws Exception {
        var repository = new MarketStatusRepository(jdbc);
        var source = mock(QuoteSource.class);
        var event = new MarketEvent("6250", Market.TPEX, LocalDate.of(2016, 5, 25),
                MarketEvent.Kind.DELISTED, "宇加");
        var cutoff = event.date().minusDays(1);
        var lastMonth = YearMonth.of(2015, 10);
        when(source.market()).thenReturn(Market.TPEX);
        for (YearMonth month = YearMonth.from(cutoff); month.isAfter(lastMonth); month = month.minusMonths(1)) {
            when(source.fetch(event.code(), month, cutoff)).thenReturn(QuoteResult.notFound());
        }
        when(source.fetch(event.code(), lastMonth, cutoff)).thenReturn(new QuoteResult(
                QuoteResult.Status.FOUND, event.name(), Market.TPEX, new BigDecimal("16.20"), lastMonth.atDay(13)));
        new DelistedQuoteHistory(repository, List.of(source)).refresh(event);

        var close = repository.finalClose(event).orElseThrow();
        assertEquals(lastMonth.atDay(13), close.priceDate());
        assertEquals(new BigDecimal("16.20"), close.close());
    }

    private MarketStatusBatch batch(MarketStatusRepository repository, OfficialMarketClient official,
                                    DelistedQuoteHistory history, LocalDate day, int hour) {
        var clock = clock(day, hour);
        var workers = mock(ExecutorService.class);
        doAnswer(task -> {
            task.<Runnable>getArgument(0).run();
            return null;
        }).when(workers).execute(any(Runnable.class));
        var sync = new MarketStatusSyncService(repository, new MarketStatusService(repository, clock), official, history);
        return new MarketStatusBatch(sync, clock, workers);
    }

    private Clock clock(LocalDate day, int hour) {
        return Clock.fixed(ZonedDateTime.of(day.atTime(hour, 0), QuoteRefreshPolicy.TAIPEI).toInstant(),
                QuoteRefreshPolicy.TAIPEI);
    }
}
