package tw.holdings.profit;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummary(BigDecimal totalCost, BigDecimal marketValue, BigDecimal pnl,
                               BigDecimal returnPct, int quoteMissing) {
    public static PortfolioSummary from(List<PositionProfit> positions) {
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        int quoteMissing = 0;

        for (PositionProfit position : positions) {
            totalCost = totalCost.add(position.totalCost());
            if (position.marketValue() == null) {
                quoteMissing++;
                continue;
            }
            marketValue = marketValue.add(position.marketValue());
            pnl = pnl.add(position.pnl());
        }

        return new PortfolioSummary(totalCost, marketValue, pnl,
                ProfitCalculator.returnPercentage(pnl, totalCost), quoteMissing);
    }
}
