package tw.holdings.holding.v2;

import java.math.BigDecimal;
import java.util.List;

import tw.holdings.profit.ProfitCalculator;

record TradePortfolioSummary(BigDecimal remainingCost, BigDecimal marketValue, BigDecimal unrealizedPnl,
                             BigDecimal unrealizedReturnPct, BigDecimal realizedPnl, BigDecimal realizedReturnPct,
                             int quoteMissing, BigDecimal delistedCost) {
    static TradePortfolioSummary from(List<TradeHoldingView> holdings) {
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal market = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal realizedCost = BigDecimal.ZERO;
        int missing = 0;
        BigDecimal delistedCost = BigDecimal.ZERO;

        for (TradeHoldingView holding : holdings) {
            cost = cost.add(holding.remainingCost());
            realized = realized.add(holding.realizedPnl());
            realizedCost = realizedCost.add(holding.realizedCost());
            if (holding.shares() == 0) {
                continue;
            }
            if (holding.delisting() != null) {
                delistedCost = delistedCost.add(holding.remainingCost());
                continue;
            }
            if (holding.currentPrice() == null) {
                missing++;
                continue;
            }
            market = market.add(holding.currentPrice().multiply(BigDecimal.valueOf(holding.shares())));
            unrealized = unrealized.add(holding.unrealizedPnl());
        }

        BigDecimal realizedRate = realizedCost.signum() == 0 ? null
                : ProfitCalculator.returnPercentage(realized, realizedCost);
        BigDecimal unrealizedRate = cost.signum() == 0 ? null : ProfitCalculator.returnPercentage(unrealized, cost);
        return new TradePortfolioSummary(cost, market, unrealized, unrealizedRate, realized, realizedRate,
                missing, delistedCost);
    }
}
