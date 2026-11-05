package com.loopers.outbox.c7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.domain.OrderConfirmed;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C-7 복구 도구 — <b>테스트 전용</b>(src/test). 운영 코드에는 W6 데이터를 다루는 코드를 넣지 않는다 (학습자 결정 11-06: 제품은 W7 기준).
 * 과제 원문: "작은 운영 진입점이나 테스트 도구로 충분".
 * <p>
 * 대상 주문 하나의 저장된 확정 사실로 order_outbox 행을 <b>없을 때만</b> 추가한다.
 * <ol>
 *   <li>W6 event_outbox에서 그 주문의 eventId·payload를 읽는다 — A의 eventId가 남은 곳은 여기뿐이다</li>
 *   <li>주문이 CONFIRMED이고, 저장된 주문(확정 시각·품목)으로 다시 만든 payload가 W6 payload와 같은지 확인한다</li>
 *   <li>order_outbox에 같은 eventId 행이 있으면 내용이 같은지만 확인한다(두 번째 복구는 아무것도 늘리지 않는다)</li>
 *   <li>없으면 W6 payload 원문 그대로, 같은 eventId로 PENDING 행을 추가한다 — 보내는 것은 relay다</li>
 * </ol>
 * 원본 사실이 없거나 서로 맞지 않으면 추측하지 않고 예외를 던진다. PG·결제·주문·예약은 읽기만 한다.
 */
class W6GapRecovery {

    enum Result { CREATED, ALREADY_PRESENT }

    private final JdbcTemplate jdbcTemplate;
    private final OrderJpaRepository orderJpaRepository;
    private final OrderOutboxPort orderOutboxPort;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    W6GapRecovery(JdbcTemplate jdbcTemplate, OrderJpaRepository orderJpaRepository, OrderOutboxPort orderOutboxPort,
                  TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderJpaRepository = orderJpaRepository;
        this.orderOutboxPort = orderOutboxPort;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    Result recover(long orderId) {
        return transactionTemplate.execute(status -> {
            List<Map<String, Object>> w6Rows = jdbcTemplate.queryForList(
                "SELECT event_id, event_type, payload FROM event_outbox WHERE aggregate_type = 'ORDER' AND aggregate_id = ?", orderId);
            if (w6Rows.size() != 1) {
                throw new IllegalStateException("원본 사실 부족·중복: 주문 " + orderId + "의 W6 발행 기록이 " + w6Rows.size() + "건입니다");
            }
            String eventId = (String) w6Rows.get(0).get("event_id");
            String eventType = (String) w6Rows.get(0).get("event_type");
            String w6Payload = (String) w6Rows.get(0).get("payload");

            OrderModel order = orderJpaRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("원본 사실 부족: 주문 " + orderId + "이 없습니다"));
            if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
                throw new IllegalStateException("충돌: 주문 " + orderId + "이 확정 상태가 아닙니다 (" + order.getStatus() + ")");
            }
            JsonNode stored = readTree(w6Payload);
            // 문자열로 직렬화한 뒤 다시 읽어 비교한다 — valueToTree는 Long을 LongNode로, readTree는 작은 수를 IntNode로 만들어
            // 같은 JSON이어도 equals가 false다 (11-06 첫 실행에서 실제로 겪음)
            JsonNode rebuilt = readTree(writeJson(OrderConfirmed.from(eventId, order)));
            if (!stored.equals(rebuilt)) {
                throw new IllegalStateException("충돌: 주문 " + orderId + "의 저장된 확정 사실과 W6 payload가 다릅니다. stored=" + stored + ", rebuilt=" + rebuilt);
            }

            Optional<OrderOutbox> existing = orderOutboxPort.findByEventId(eventId);
            if (existing.isPresent()) {
                if (!existing.get().getOrderId().equals(orderId) || !readTree(existing.get().getPayload()).equals(stored)) {
                    throw new IllegalStateException("충돌: eventId " + eventId + "의 Outbox 행 내용이 원본과 다릅니다");
                }
                return Result.ALREADY_PRESENT;
            }
            orderOutboxPort.save(OrderOutbox.pending(eventId, orderId, eventType, stored.get("schemaVersion").asInt(), w6Payload, ZonedDateTime.now()));
            return Result.CREATED;
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("payload를 다시 만들지 못했습니다", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("원본 payload를 읽지 못했습니다: " + json, e);
        }
    }
}
