package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventSerializer;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.stereotype.Component;

/**
 * 확정 이벤트를 W6 producer가 보내던 것과 같은 모양의 JSON으로 만든다.
 *
 * <p>W6의 공통 {@code JsonSerializer}가 쓰던 spring-kafka 기본 mapper를 그대로 쓴다. 그래서 relay가 이 값을 그대로 보내면 W6 broker record 값과 같고,
 * 웹 응답용 {@code ObjectMapper} 설정이 바뀌어도 이미 저장한 사실의 모양은 바뀌지 않는다.
 */
@Component
public class JacksonOrderEventSerializer implements OrderEventSerializer {

    private final ObjectMapper objectMapper = JacksonUtils.enhancedObjectMapper();

    @Override
    public String serialize(OrderConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OrderConfirmed를 Outbox 값으로 직렬화하지 못했습니다. eventId=" + event.eventId(), e);
        }
    }
}
