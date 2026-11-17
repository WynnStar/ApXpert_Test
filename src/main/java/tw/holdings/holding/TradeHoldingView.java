package tw.holdings.holding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import tw.holdings.profit.ProfitCalculator;
import tw.holdings.quote.Market;
import tw.holdings.trade.TradeEntry;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record TradeHoldingView(long id, String code, String name, Market market, boolean v2Managed,
                        long shares, BigDecimal remainingCost, BigDecimal averageCost,
                        BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal currentPrice,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate priceDate) {
    public BigDecimal displayAverageCost() {
        return shares == 0 ? null : remainingCost.divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal unrealizedReturnPct() {
        return unrealizedPnl == null ? null : ProfitCalculator.returnPercentage(unrealizedPnl, remainingCost);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record TradeDetail(String side, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate tradeDate,
                       long shares, BigDecimal unitPrice, @JsonIgnore long id,
                       @JsonIgnore boolean canMoveUp, @JsonIgnore boolean canMoveDown) {
        static List<TradeDetail> from(List<TradeEntry> entries) {
            return IntStream.range(0, entries.size()).mapToObj(index -> {
                var entry = entries.get(index);
                var trade = entry.trade();
                boolean canMoveUp = index > 0 && entry.sameDayAs(entries.get(index - 1));
                boolean canMoveDown = index + 1 < entries.size() && entry.sameDayAs(entries.get(index + 1));
                return new TradeDetail(trade.side().name(), trade.tradeDate(), trade.shares(), trade.unitPrice(),
                        entry.id(), canMoveUp, canMoveDown);
            }).toList();
        }
    }

    record History(TradeHoldingView holding, List<TradeDetail> trades) {
    }
}
