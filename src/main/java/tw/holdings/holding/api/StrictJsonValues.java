package tw.holdings.holding.api;

import java.io.IOException;
import java.math.BigDecimal;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

public final class StrictJsonValues {
    private StrictJsonValues() {
    }

    public static final class TextValue extends StdDeserializer<String> {
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

    public static final class IntegerValue extends StdDeserializer<Integer> {
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

    public static final class LongValue extends StdDeserializer<Long> {
        LongValue() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "Expected a JSON integer");
            }
            return parser.getLongValue();
        }
    }

    public static final class DecimalValue extends StdDeserializer<BigDecimal> {
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
