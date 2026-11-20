package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tw.holdings.quote.marketstatus.MarketStatusService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteTimezoneTest {
    @Test
    void productionClockUsesTaipeiRatherThanTheHostDefault() {
        assertEquals(ZoneId.of("Asia/Taipei"), new QuoteWorkerConfig().quoteClock().getZone());
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-17T15:59:59Z, 2026-11-17",
        "2026-11-17T16:00:00Z, 2026-11-18",
        "2026-11-30T16:00:00Z, 2026-12-01"
    })
    void quoteLookupAndDisplayUseTaipeiCacheDate(String instant, String localDate) {
        var day = LocalDate.parse(localDate);
        var cache = mock(QuoteCacheRepository.class);
        var result = QuoteResult.found("台積電", Market.TWSE, new BigDecimal("100.00"), day);
        when(cache.find(day, "2330")).thenReturn(Optional.of(
                new QuoteCacheRepository.Entry(result, 0, 0, null)));
        var workers = mock(ExecutorService.class);
        var official = mock(OfficialQuoteLookup.class);
        var batch = mock(BatchQuoteService.class);
        var service = new QuoteService(cache, official,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), workers,
                mock(MarketStatusService.class), batch);

        assertEquals(result, service.lookup("2330", Market.TWSE));
        assertEquals(result, service.forDisplay("2330", Market.TWSE));
        verify(cache, times(2)).find(day, "2330");
        verifyNoInteractions(workers, official, batch);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-17T05:34:59Z, 0",
        "2026-11-17T05:35:00Z, 1",
        "2026-11-17T07:04:59Z, 1",
        "2026-11-17T07:05:00Z, 2",
        "2026-11-20T16:00:00Z, 0"
    })
    void refreshSchedulerUsesTaipeiSlotsAndTaipeiWeekend(String instant, int slot) {
        var fixed = Instant.parse(instant);
        var taipei = fixed.atZone(QuoteRefreshPolicy.TAIPEI);
        var cache = mock(QuoteCacheRepository.class);
        var service = new QuoteService(cache, mock(OfficialQuoteLookup.class),
                Clock.fixed(fixed, ZoneOffset.UTC), mock(ExecutorService.class),
                mock(MarketStatusService.class), mock(BatchQuoteService.class));

        assertEquals(slot, QuoteRefreshPolicy.refreshSlot(taipei));
        service.refreshPendingCloses();
        if (slot == 0) {
            verifyNoInteractions(cache);
        } else {
            verify(cache).dueAfterClose(taipei);
            assertEquals(Instant.parse(slot == 1 ? "2026-11-17T05:35:00Z" : "2026-11-17T07:05:00Z"),
                    QuoteRefreshPolicy.slotStart(taipei));
        }
    }

    @Test
    void nextRefreshAfterFridaySecondSlotIsMonday1335Taipei() {
        ZonedDateTime friday = Instant.parse("2026-11-20T07:05:00Z").atZone(QuoteRefreshPolicy.TAIPEI);
        assertEquals(Instant.parse("2026-11-23T05:35:00Z"),
                QuoteRefreshPolicy.nextAttemptAt(friday, false));
    }
}
