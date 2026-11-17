package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class MarketStatusService {
    public record Delisting(Market market, String name, LocalDate effectiveDate,
                            BigDecimal lastClose, LocalDate lastPriceDate) {
        public String label() {
            return market == Market.TWSE ? "已下市" : "已下櫃";
        }
    }

    private final MarketStatusRepository repository;
    private final Clock clock;

    MarketStatusService(MarketStatusRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<Delisting> delisting(String code) {
        return listingEvent(code, today()).filter(event -> event.kind() == MarketEvent.Kind.DELISTED)
                .map(event -> {
                    var price = repository.finalClose(event).orElse(null);
                    return new Delisting(event.market(), event.name(), event.date(), price == null ? null : price.close(),
                            price == null ? null : price.priceDate());
                });
    }

    public Optional<String> buyRestriction(String code, LocalDate date) {
        var events = repository.events(code, date);
        var listing = listingEvent(events).orElse(null);
        if (listing != null && listing.kind() == MarketEvent.Kind.DELISTED) {
            String label = listing.market() == Market.TWSE ? "下市" : "下櫃";
            return Optional.of(code + " 自 " + listing.date() + " 起" + label + "，無法登記該日或之後的買進");
        }
        var halt = events.stream().findFirst().filter(event -> event.kind() == MarketEvent.Kind.HALTED);
        if (halt.isPresent()) {
            return Optional.of(code + " 在 " + date + " 停止買賣，無法登記買進");
        }
        return repository.finalTradingDayBeforeDelisting(code, date)
                .map(lastDay -> code + " 的最後交易日為 " + lastDay + "，無法登記之後的買進");
    }

    Optional<MarketEvent> listingEvent(String code, LocalDate date) {
        return listingEvent(repository.events(code, date));
    }

    private Optional<MarketEvent> listingEvent(List<MarketEvent> events) {
        return events.stream().filter(event -> event.kind() == MarketEvent.Kind.DELISTED
                || event.kind() == MarketEvent.Kind.RELISTED).findFirst();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), QuoteRefreshPolicy.TAIPEI);
    }
}
