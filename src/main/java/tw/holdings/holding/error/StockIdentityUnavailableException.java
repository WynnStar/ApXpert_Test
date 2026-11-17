package tw.holdings.holding.error;

public final class StockIdentityUnavailableException extends RuntimeException {
    public StockIdentityUnavailableException(String code) {
        super("暫時無法取得 " + code + " 的股票名稱與市場，請稍後再試");
    }
}
