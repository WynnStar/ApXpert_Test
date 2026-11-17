package tw.holdings.holding;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import tw.holdings.trade.Trade;

record TradeEditForm(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
                     @NotNull(message = "請填寫股數") @Positive(message = "股數必須大於 0") Long shares,
                     @NotNull(message = "請填寫每股價格")
                     @DecimalMin(value = "0", inclusive = false, message = "每股價格必須大於 0")
                     @Digits(integer = 36, fraction = 2, message = "每股價格最多兩位小數") BigDecimal unitPrice,
                     Boolean confirmV2) {
    static TradeEditForm from(Trade trade) {
        return new TradeEditForm(trade.tradeDate(), trade.shares(), trade.unitPrice(), false);
    }

    Trade applyTo(Trade.Side side) {
        if (side == Trade.Side.OPENING && tradeDate != null) {
            throw new HoldingValidationException("期初持股無交易日期");
        }
        if (side != Trade.Side.OPENING && tradeDate == null) {
            throw new HoldingValidationException("請填寫交易日期");
        }
        return new Trade(side, tradeDate, shares, unitPrice);
    }
}
