package com.loopers.couponissue.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.loopers.couponissue.domain.CouponIssueMessage;
import com.loopers.couponissue.domain.InvalidCouponIssueMessageException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * 발급 메시지 JSON을 엄격하게 읽는다(W6 OrderConfirmedMessageParser와 같은 이유 — 문자열 숫자·실수를 정수로 바꿔 읽지 않는다).
 */
@Component
public class CouponIssueMessageParser {

    private final ObjectMapper objectMapper;

    public CouponIssueMessageParser(ObjectMapper objectMapper) {
        ObjectMapper strict = objectMapper.copy()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        strict.coercionConfigFor(LogicalType.Integer)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        this.objectMapper = strict;
    }

    public CouponIssueMessage parse(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), CouponIssueMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidCouponIssueMessageException(
                "발급 메시지로 읽을 수 없습니다. topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset(), e);
        }
    }
}
