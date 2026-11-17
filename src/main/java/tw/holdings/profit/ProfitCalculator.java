package tw.holdings.profit;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ProfitCalculator {
    private static final BigDecimal SHARES_PER_LOT = BigDecimal.valueOf(1_000);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
    private static final BigDecimal MIN_FEE = BigDecimal.valueOf(20);
    private static final BigDecimal STOCK_TAX_RATE = new BigDecimal("0.003");
    private static final BigDecimal ETF_TAX_RATE = new BigDecimal("0.001");

    private ProfitCalculator() {
    }

    public static PositionProfit calculate(String code, int lots, BigDecimal buyPrice, BigDecimal close) {
        BigDecimal shares = BigDecimal.valueOf(lots).multiply(SHARES_PER_LOT);
        BigDecimal buyAmount = buyPrice.multiply(shares).setScale(0, RoundingMode.UNNECESSARY);
        BigDecimal totalCost = buyAmount.add(fee(buyAmount));
        if (close == null) {
            return new PositionProfit(totalCost, null, null, null);
        }

        BigDecimal marketValue = close.multiply(shares).setScale(0, RoundingMode.UNNECESSARY);
        BigDecimal taxRate = code.startsWith("00") ? ETF_TAX_RATE : STOCK_TAX_RATE;
        BigDecimal sellTax = marketValue.multiply(taxRate).setScale(0, RoundingMode.DOWN);
        BigDecimal pnl = marketValue.subtract(fee(marketValue)).subtract(sellTax).subtract(totalCost);
        return new PositionProfit(totalCost, marketValue, pnl, returnPercentage(pnl, totalCost));
    }

    static BigDecimal returnPercentage(BigDecimal pnl, BigDecimal totalCost) {
        if (totalCost.signum() == 0) {
            return new BigDecimal("0.00");
        }
        return pnl.movePointRight(2).divide(totalCost, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal fee(BigDecimal amount) {
        return amount.multiply(FEE_RATE).setScale(0, RoundingMode.DOWN).max(MIN_FEE);
    }
}
