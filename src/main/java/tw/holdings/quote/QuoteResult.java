package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

public record QuoteResult(Status status, String name, Market market,
                          BigDecimal close, LocalDate priceDate) {
    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    static QuoteResult found(String name, Market market, OfficialQuoteData.Price price) {
        return new QuoteResult(Status.FOUND, name, market,
                price == null ? null : price.close(), price == null ? null : price.date());
    }

    static QuoteResult notFound() {
        return new QuoteResult(Status.NOT_FOUND, null, null, null, null);
    }

    static QuoteResult unavailable() {
        return new QuoteResult(Status.UNAVAILABLE, null, null, null, null);
    }
}
