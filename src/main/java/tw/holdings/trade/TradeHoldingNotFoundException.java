package tw.holdings.trade;

public class TradeHoldingNotFoundException extends RuntimeException {
    TradeHoldingNotFoundException(long id) {
        super("找不到持股 #" + id);
    }
}
