package tw.holdings.holding;

class HoldingTradeNotFoundException extends RuntimeException {
    HoldingTradeNotFoundException(long id) {
        super("找不到這檔持股的交易紀錄 #" + id);
    }
}
