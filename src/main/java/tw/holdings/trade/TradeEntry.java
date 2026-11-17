package tw.holdings.trade;

public record TradeEntry(long id, long sortOrder, Trade trade) {
    public boolean sameDayAs(TradeEntry other) {
        return trade.tradeDate() != null && trade.tradeDate().equals(other.trade.tradeDate());
    }
}
