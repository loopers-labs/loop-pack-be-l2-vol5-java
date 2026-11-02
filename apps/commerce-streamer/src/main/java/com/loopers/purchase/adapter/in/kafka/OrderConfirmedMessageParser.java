package com.loopers.purchase.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.loopers.purchase.domain.InvalidOrderConfirmedException;
import com.loopers.purchase.domain.OrderConfirmedMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * record 값(JSON 원문)을 OrderConfirmed v1로 읽는다. 계약에 맞지 않으면 InvalidOrderConfirmedException — 재시도 없이 DLT (ADR-W6-06).
 * listener와 재전달 실험(E-2)이 같은 파싱을 쓰도록 따로 둔다.
 * <p>
 * 앱 공용 ObjectMapper를 복사해 이 parser만 엄격하게 쓴다 — 공용 설정은 실수를 정수로(1.9 → 1), 문자열 숫자를 숫자로,
 * 객체 하나를 배열로 바꿔 읽는다. 구매 사실을 바꿔 읽으면 조용히 틀린 값이 저장되므로 형식이 다르면 읽지 않는다 (R-6).
 */
@Component
public class OrderConfirmedMessageParser {

    private final ObjectMapper objectMapper;

    public OrderConfirmedMessageParser(ObjectMapper objectMapper) {
        ObjectMapper strict = objectMapper.copy()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        strict.coercionConfigFor(LogicalType.Integer)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        this.objectMapper = strict;
    }

    public OrderConfirmedMessage parse(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), OrderConfirmedMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidOrderConfirmedException(
                "OrderConfirmed v1로 읽을 수 없습니다. topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset(), e);
        }
    }
}
