package tw.holdings.trade;

import java.util.List;
import java.util.Optional;

public record TradeEntry(long id, long sortOrder, Trade trade) {
    public static Optional<TradeEntry> findById(List<TradeEntry> entries, long id) {
        return entries.stream().filter(entry -> entry.id() == id).findFirst();
    }

    public boolean sameDayAs(TradeEntry other) {
        return trade.tradeDate() != null && trade.tradeDate().equals(other.trade.tradeDate());
    }
}
