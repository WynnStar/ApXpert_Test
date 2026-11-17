package tw.holdings.holding;

import tw.holdings.quote.QuoteResult;

class StockLookupException extends RuntimeException {
    private final QuoteResult.Status status;

    StockLookupException(QuoteResult.Status status) {
        super(status == QuoteResult.Status.NOT_FOUND
                ? "查無此股票代號"
                : "暫時無法驗證股票代號，請稍後再試");
        this.status = status;
    }

    QuoteResult.Status status() {
        return status;
    }
}
