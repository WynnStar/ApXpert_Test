package tw.holdings.holding;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

record HoldingApiRequest(
        @JsonDeserialize(using = TextValue.class) String code,
        @JsonDeserialize(using = IntegerValue.class) Integer lots,
        @JsonProperty("buy_price") @JsonDeserialize(using = DecimalValue.class) BigDecimal buyPrice) {

    HoldingTermsForm terms() {
        return new HoldingTermsForm(lots, buyPrice);
    }

    static class TextValue extends StdDeserializer<String> {
        TextValue() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "Expected a JSON string");
            }
            return parser.getText();
        }
    }

    static class IntegerValue extends StdDeserializer<Integer> {
        IntegerValue() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "Expected a JSON integer");
            }
            return parser.getIntValue();
        }
    }

    static class DecimalValue extends StdDeserializer<BigDecimal> {
        DecimalValue() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.currentToken().isNumeric()) {
                return context.reportInputMismatch(BigDecimal.class, "Expected a JSON number");
            }
            return parser.getDecimalValue();
        }
    }
}
