package tw.holdings.holding.legacy;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import tw.holdings.holding.api.StrictJsonValues;

record HoldingApiRequest(
        @JsonDeserialize(using = StrictJsonValues.TextValue.class) String code,
        @JsonDeserialize(using = StrictJsonValues.IntegerValue.class) Integer lots,
        @JsonProperty("buy_price") @JsonDeserialize(using = StrictJsonValues.DecimalValue.class) BigDecimal buyPrice) {

    HoldingTermsForm terms() {
        return new HoldingTermsForm(lots, buyPrice);
    }
}
