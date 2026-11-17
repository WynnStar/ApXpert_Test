package tw.holdings.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;

import tw.holdings.profit.TradeCharges;

public record TradePosition(long shares, BigDecimal remainingCost, BigDecimal realizedPnl) {
    public BigDecimal averageCost() {
        if (shares == 0) {
            return null;
        }
        return remainingCost.divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal unrealizedPnl(String code, BigDecimal close) {
        if (shares == 0) {
            return new BigDecimal("0.00");
        }
        if (close == null) {
            return null;
        }
        BigDecimal marketValue = close.multiply(BigDecimal.valueOf(shares));
        return marketValue.subtract(TradeCharges.fee(marketValue))
                .subtract(TradeCharges.sellTax(code, marketValue))
                .subtract(remainingCost).setScale(2, RoundingMode.HALF_UP);
    }
}
