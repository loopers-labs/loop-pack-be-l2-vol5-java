package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderConfirmedMessageEncoder;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 확정 사건을 기존 OrderConfirmed v1 wire 본문으로 한 번 직렬화한다(설계 W7.2-6). 앱의 공통 ObjectMapper 를 그대로 쓴다.
 * wire 필드·숫자 타입·confirmedAt 의 ISO_INSTANT(UTC Z·최대 마이크로초) 표현은 W6 발행 adapter 의 DTO 와 같다.
 * 조회·send·TX 시작은 없다. 실패는 원문 payload·예외 메시지 없이 예외 클래스명만 담은 INTERNAL_ERROR 로 전파한다.
 */
@Component
public class JacksonOrderConfirmedMessageEncoder implements OrderConfirmedMessageEncoder {

    private final ObjectMapper objectMapper;

    public JacksonOrderConfirmedMessageEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public OrderOutboxMessage encode(OrderConfirmed event) {
        if (event == null || !OrderModel.isUtcMicros(event.confirmedAt()) || event.items().isEmpty()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "Outbox 로 기록할 확정 사건이 올바르지 않습니다.");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(OrderConfirmedV1.from(event));
        } catch (JsonProcessingException | RuntimeException failure) {
            // cause 를 연결하면 원문 메시지가 stacktrace 로 노출되므로 클래스명만 남긴다.
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "확정 사건을 v1 본문으로 직렬화하지 못했습니다. [exceptionType=" + failure.getClass().getName() + "]");
        }
        return new OrderOutboxMessage(event.eventId(), event.orderId(), OrderOutboxMessage.EVENT_TYPE,
            OrderOutboxMessage.SCHEMA_VERSION, Long.toString(event.orderId()), json);
    }

    /** OrderConfirmed v1 wire 계약. 이 adapter 가 소유하며 다른 계층에 노출하지 않는다. */
    record OrderConfirmedV1(String eventType, int schemaVersion, String eventId, long orderId, String confirmedAt,
                            List<Item> items) {

        static OrderConfirmedV1 from(OrderConfirmed event) {
            return new OrderConfirmedV1(OrderOutboxMessage.EVENT_TYPE, OrderOutboxMessage.SCHEMA_VERSION, event.eventId(),
                event.orderId(), DateTimeFormatter.ISO_INSTANT.format(event.confirmedAt().toInstant()),
                event.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList());
        }

        record Item(long productId, long quantity) {
        }
    }
}
