package com.loopers.infrastructure.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.gateway.OrderConfirmedSerializer;
import com.loopers.domain.order.vo.OrderConfirmed;
import org.springframework.stereotype.Component;

/** 앱 공용 ObjectMapper 설정이 바뀌어도 저장 payload 가 흔들리지 않게 기본 설정 mapper 를 따로 씀. 형식은 메시지 DTO 가 고정함 (6주차 D-83) */
@Component
public class JsonOrderConfirmedSerializer implements OrderConfirmedSerializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String serialize(OrderConfirmed event) {
        try {
            return OBJECT_MAPPER.writeValueAsString(OrderConfirmedMessage.from(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 확정 이벤트 직렬화 실패. eventId=" + event.eventId(), e);
        }
    }
}
