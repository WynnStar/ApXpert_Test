package tw.holdings.holding.legacy;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record HoldingTermsForm(
        @NotNull(message = "請輸入張數")
        @Min(value = 1, message = "張數須在 1 到 999 之間")
        @Max(value = 999, message = "張數須在 1 到 999 之間") Integer lots,
        @NotNull(message = "請輸入買進價")
        @DecimalMin(value = "0", inclusive = false, message = "買進價須大於 0")
        @Digits(integer = 36, fraction = 2, message = "買進價最多兩位小數，整數部分最多 36 位")
        BigDecimal buyPrice) {
}
