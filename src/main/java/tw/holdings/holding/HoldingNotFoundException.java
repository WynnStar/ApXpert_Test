package tw.holdings.holding;

class HoldingNotFoundException extends RuntimeException {
    HoldingNotFoundException(long id) {
        super("找不到持股 #" + id);
    }
}
