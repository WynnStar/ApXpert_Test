package tw.holdings.holding;

import java.math.BigDecimal;
import java.util.List;

import tw.holdings.profit.ProfitCalculator;

record TradePortfolioSummary(BigDecimal remainingCost, BigDecimal marketValue, BigDecimal unrealizedPnl,
                             BigDecimal returnPct, BigDecimal realizedPnl, int quoteMissing) {
    static TradePortfolioSummary from(List<TradeHoldingView> holdings) {
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal market = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        int missing = 0;

        for (TradeHoldingView holding : holdings) {
            cost = cost.add(holding.remainingCost());
            realized = realized.add(holding.realizedPnl());
            if (holding.shares() == 0) {
                continue;
            }
            if (holding.currentPrice() == null) {
                missing++;
                continue;
            }
            market = market.add(holding.currentPrice().multiply(BigDecimal.valueOf(holding.shares())));
            unrealized = unrealized.add(holding.unrealizedPnl());
        }

        return new TradePortfolioSummary(cost, market, unrealized,
                ProfitCalculator.returnPercentage(unrealized, cost), realized, missing);
    }
}
