package tw.holdings.quote;

import java.math.BigDecimal;
import java.time.LocalDate;

public record QuoteResult(Status status, String name, Market market,
                          BigDecimal close, LocalDate priceDate) {
    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public static QuoteResult found(String name, Market market, BigDecimal close, LocalDate priceDate) {
        return new QuoteResult(Status.FOUND, name, market, close, priceDate);
    }

    public static QuoteResult notFound() {
        return new QuoteResult(Status.NOT_FOUND, null, null, null, null);
    }

    public static QuoteResult unavailable() {
        return new QuoteResult(Status.UNAVAILABLE, null, null, null, null);
    }
}
