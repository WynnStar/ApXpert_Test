package tw.holdings.holding.error;

import tw.holdings.quote.QuoteResult;

public final class StockLookupException extends RuntimeException {
    private final QuoteResult.Status status;

    public StockLookupException(QuoteResult.Status status) {
        this(status, status == QuoteResult.Status.NOT_FOUND
                ? "查無此股票代號"
                : "暫時無法驗證股票代號，請稍後再試");
    }

    public StockLookupException(QuoteResult.Status status, String message) {
        super(message);
        this.status = status;
    }

    public QuoteResult.Status status() {
        return status;
    }
}
