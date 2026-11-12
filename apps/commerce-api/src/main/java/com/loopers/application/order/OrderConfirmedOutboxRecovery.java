package com.loopers.application.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRecovery {

    private final OrderConfirmedOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recoverIfMissing(OrderConfirmedEvent event) {
        outboxRepository.findByEventId(event.eventId()).ifPresentOrElse(
            existing -> {
                String existingFingerprint = fingerprint(existing.getPayload());
                String newFingerprint = fingerprint(toPayloadJson(event));
                if (!existingFingerprint.equals(newFingerprint)) {
                    throw new CoreException(ErrorType.BAD_REQUEST,
                        "복구 대상 이벤트의 payload가 기존 Outbox와 충돌합니다: eventId=" + event.eventId());
                }
                log.info("Outbox 이미 존재, 내용 일치 확인: eventId={}", event.eventId());
            },
            () -> {
                String payload = toPayloadJson(event);
                OrderConfirmedOutbox outbox = OrderConfirmedOutbox.create(
                    event.eventId(),
                    event.orderId(),
                    String.valueOf(event.orderId()),
                    payload
                );
                outboxRepository.save(outbox);
                log.info("Outbox 복구 삽입: eventId={}, orderId={}", event.eventId(), event.orderId());
            }
        );
    }

    private String toPayloadJson(OrderConfirmedEvent event) {
        try {
            ObjectNode node = objectMapper.valueToTree(event);
            node.put("eventType", "ORDER_CONFIRMED");
            node.put("schemaVersion", 1);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "Outbox payload 직렬화 실패: " + e.getMessage());
        }
    }

    private String fingerprint(String payloadJson) {
        return payloadJson == null ? "" : payloadJson;
    }
}
