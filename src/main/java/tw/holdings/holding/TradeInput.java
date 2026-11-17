package tw.holdings.holding;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import tw.holdings.trade.Trade;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record TradeInput(@JsonDeserialize(using = HoldingApiRequest.TextValue.class) String code, Trade.Side side,
                  @NotNull(message = "請填寫交易日期")
                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
                  @NotNull(message = "請填寫股數")
                  @Positive(message = "股數必須大於 0") @JsonDeserialize(using = HoldingApiRequest.LongValue.class) Long shares,
                  @NotNull(message = "請填寫每股價格")
                  @DecimalMin(value = "0", inclusive = false, message = "每股價格必須大於 0")
                  @Digits(integer = 36, fraction = 2, message = "每股價格最多兩位小數")
                  @JsonDeserialize(using = HoldingApiRequest.DecimalValue.class) BigDecimal unitPrice,
                  Boolean confirmV2) {
    String stockCode() {
        if (code == null || !code.matches(CreateHoldingForm.CODE_PATTERN)) {
            throw new HoldingValidationException("請填寫合法股票代號");
        }
        return code;
    }

    Trade firstBuy() {
        if (side != null && side != Trade.Side.BUY) {
            throw new HoldingValidationException("建立持股時須先記錄一筆買進");
        }
        return new Trade(Trade.Side.BUY, tradeDate, shares, unitPrice);
    }

    Trade trade() {
        if (side == null || side == Trade.Side.OPENING) {
            throw new HoldingValidationException("請選擇買進或賣出");
        }
        return new Trade(side, tradeDate, shares, unitPrice);
    }

    boolean confirmed() {
        return Boolean.TRUE.equals(confirmV2);
    }
}
