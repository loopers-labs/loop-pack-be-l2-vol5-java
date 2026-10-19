package com.loopers.interfaces.api.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.math.BigDecimal;

public class ExactIntegerDeserializer extends StdDeserializer<Integer> {
    public ExactIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
            return context.readValue(parser, Integer.class);
        }
        BigDecimal value = parser.getDecimalValue();
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw InvalidFormatException.from(parser, "저장 가능한 범위의 정수여야 합니다.", value, Integer.class);
        }
    }

    @Override
    public Integer getNullValue(DeserializationContext context) throws JsonMappingException {
        return context.reportInputMismatch(Integer.class, "정수 입력은 필수입니다.");
    }
}
