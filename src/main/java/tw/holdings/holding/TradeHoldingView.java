package tw.holdings.holding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import tw.holdings.profit.ProfitCalculator;
import tw.holdings.quote.Market;
import tw.holdings.trade.Trade;

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
                       long shares, BigDecimal unitPrice) {
        static TradeDetail from(Trade trade) {
            return new TradeDetail(trade.side().name(), trade.tradeDate(), trade.shares(), trade.unitPrice());
        }
    }

    record History(TradeHoldingView holding, List<TradeDetail> trades) {
    }
}
