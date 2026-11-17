package tw.holdings.profit;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TradeCharges {
    private static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
    private static final BigDecimal MIN_FEE = BigDecimal.valueOf(20);
    private static final BigDecimal STOCK_TAX_RATE = new BigDecimal("0.003");
    private static final BigDecimal ETF_TAX_RATE = new BigDecimal("0.001");

    private TradeCharges() {
    }

    public static BigDecimal fee(BigDecimal amount) {
        return amount.multiply(FEE_RATE).setScale(0, RoundingMode.DOWN).max(MIN_FEE);
    }

    public static BigDecimal sellTax(String code, BigDecimal amount) {
        BigDecimal taxRate = code.startsWith("00") ? ETF_TAX_RATE : STOCK_TAX_RATE;
        return amount.multiply(taxRate).setScale(0, RoundingMode.DOWN);
    }
}
