package tw.holdings.quote;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class DelistedQuoteHistory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DelistedQuoteHistory.class);
    private static final int MONTHS_PER_DAY = 8;
    private final MarketStatusRepository repository;
    private final List<QuoteSource> sources;

    DelistedQuoteHistory(MarketStatusRepository repository, List<QuoteSource> sources) {
        this.repository = repository;
        this.sources = sources;
    }

    void refresh(MarketEvent event) throws InterruptedException {
        LocalDate cutoff = event.date().minusDays(1);
        var saved = repository.finalClose(event).orElse(null);
        if (saved != null && saved.priceDate() != null) {
            return;
        }
        var month = YearMonth.from(saved == null ? cutoff : saved.nextMonth());
        var firstPublished = event.market() == Market.TWSE ? YearMonth.of(2010, 1) : YearMonth.of(1994, 1);
        var source = sources.stream().filter(client -> client.market() == event.market()).findFirst().orElseThrow();

        for (int attempt = 0; attempt < MONTHS_PER_DAY && !month.isBefore(firstPublished); attempt++) {
            try {
                var quote = source.fetch(event.code(), month, cutoff);
                if (quote.status() == QuoteResult.Status.UNAVAILABLE) {
                    return;
                }
                var previous = month.minusMonths(1);
                if (quote.close() != null && quote.priceDate() != null && !quote.priceDate().isAfter(cutoff)) {
                    repository.saveFinalClose(event, new MarketStatusRepository.FinalClose(
                            previous.atDay(1), quote.priceDate(), quote.close()));
                    return;
                }
                repository.saveFinalClose(event, new MarketStatusRepository.FinalClose(previous.atDay(1), null, null));
                month = previous;
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("Final closing price fetch failed: market={}, code={}, month={}, reason={}",
                        event.market(), event.code(), month, error.getClass().getSimpleName());
                return;
            }
        }
    }
}
