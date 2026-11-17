package tw.holdings.holding.v2;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tw.holdings.holding.api.StrictJsonValues;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record TradeUpdateRequest(LocalDate tradeDate,
                          @NotNull(message = "請填寫股數")
                          @Positive(message = "股數必須大於 0")
                          @JsonDeserialize(using = StrictJsonValues.LongValue.class) Long shares,
                          @NotNull(message = "請填寫每股價格")
                          @DecimalMin(value = "0", inclusive = false, message = "每股價格必須大於 0")
                          @Digits(integer = 36, fraction = 2, message = "每股價格最多兩位小數")
                          @JsonDeserialize(using = StrictJsonValues.DecimalValue.class) BigDecimal unitPrice,
                          Boolean confirmV2) {
    TradeEditForm toForm() {
        return new TradeEditForm(tradeDate, shares, unitPrice, confirmV2);
    }
}
