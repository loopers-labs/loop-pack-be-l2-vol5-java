package com.loopers.purchase.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.purchase.domain.InvalidOrderConfirmedException;
import com.loopers.purchase.domain.OrderConfirmedMessage;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * record 값(JSON 원문)을 OrderConfirmed v1로 읽는다. 계약에 맞지 않으면 InvalidOrderConfirmedException — 재시도 없이 DLT (ADR-W6-06).
 * listener와 재전달 실험(E-2)이 같은 파싱을 쓰도록 따로 둔다.
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedMessageParser {

    private final ObjectMapper objectMapper;

    public OrderConfirmedMessage parse(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), OrderConfirmedMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidOrderConfirmedException(
                "OrderConfirmed v1로 읽을 수 없습니다. topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset(), e);
        }
    }
}
