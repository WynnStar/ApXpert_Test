package tw.holdings.holding;

class StockIdentityUnavailableException extends RuntimeException {
    StockIdentityUnavailableException(String code) {
        super("暫時無法取得 " + code + " 的股票名稱與市場，請稍後再試");
    }
}
