package tw.holdings.trade;

public class TradeOrderException extends RuntimeException {
    TradeOrderException() {
        super("只能調整這檔持股同一天的相鄰交易。");
    }
}
