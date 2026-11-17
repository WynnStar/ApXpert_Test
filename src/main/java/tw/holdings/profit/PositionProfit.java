package tw.holdings.profit;

import java.math.BigDecimal;

public record PositionProfit(BigDecimal totalCost, BigDecimal marketValue,
                             BigDecimal pnl, BigDecimal returnPct) {
}
