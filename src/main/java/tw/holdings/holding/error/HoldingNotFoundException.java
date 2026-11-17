package tw.holdings.holding.error;

public final class HoldingNotFoundException extends RuntimeException {
    public HoldingNotFoundException(long id) {
        super("找不到持股 #" + id);
    }
}
