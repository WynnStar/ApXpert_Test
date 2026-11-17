package tw.holdings.holding;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateHoldingForm(
        @NotBlank(message = "請輸入股票代號")
        @Pattern(regexp = "[0-9]{4}[0-9A-Z]{0,2}", message = "股票代號須為 4 至 6 碼，前 4 碼為數字，後續限數字或大寫英文字母") String code,
        @Valid @NotNull HoldingTermsForm terms) {

    static CreateHoldingForm empty() {
        return new CreateHoldingForm("", new HoldingTermsForm(null, null));
    }

    Integer lots() {
        return terms.lots();
    }

    BigDecimal buyPrice() {
        return terms.buyPrice();
    }
}
