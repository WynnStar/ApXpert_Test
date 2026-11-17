package tw.holdings.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record Trade(Side side, LocalDate tradeDate, long shares, BigDecimal unitPrice) {
    public enum Side { BUY, SELL }

    public Trade {
        Objects.requireNonNull(side, "交易方向為必填");
        Objects.requireNonNull(tradeDate, "交易日期為必填");
        Objects.requireNonNull(unitPrice, "每股價格為必填");
        if (shares <= 0) {
            throw new IllegalArgumentException("交易股數必須大於 0");
        }
        if (unitPrice.signum() <= 0 || unitPrice.scale() > 2 || unitPrice.precision() - (long) unitPrice.scale() > 36) {
            throw new IllegalArgumentException("每股價格須大於 0，且最多兩位小數、36 位整數");
        }
    }
}
