package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderConfirmedPayloadFactory;
import com.loopers.domains.order.app.OrderOutboxBackfill;
import com.loopers.domains.order.app.OrderOutboxBackfill.Outcome;
import com.loopers.domains.order.app.OrderOutboxBackfill.Result;
import com.loopers.domains.order.app.port.OrderRepository;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 5단계 A(D121): 확정은 됐는데 Outbox 행이 없는 주문에, 1단계와 같은 팩토리·직렬화기로 만든 같은 eventId 행을 채운다.
// "Outbox 기록이 없는 확정"은 테스트 설정의 append 대역(skipAppends)으로 만든다. 실제 6주차 실행물이 아니다.
// 복구는 PG를 부르지 않고 주문·예약·결제 기록을 바꾸지 않는다. 결과는 DB 새 조회로 본다.
class OrderOutboxBackfillIntegrationTest extends OrderEventsIntegrationSupport {
    @Autowired private OrderOutboxBackfill orderOutboxBackfill;
    @Autowired private OrderRepository orderRepository;

    @Test
    @DisplayName("Outbox 행이 없는 확정 주문을 복구하면 ADDED이고 같은 eventId의 PENDING 1행이 1단계와 같은 payload로 생기며 주문·결제·재고·PG 호출은 그대로다")
    void missingRowIsAdded() throws Exception {
        long orderId = confirmedWithoutOutbox();
        Map<String, Object> paymentBefore = payment(orderId);
        long createCallsBefore = server.createCalls();

        List<Outcome> outcomes = orderOutboxBackfill.ensure(List.of(orderId));

        assertThat(outcomes).containsExactly(new Outcome(orderId, Result.ADDED));
        assertThat(rows(orderId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("event_id", eventId(orderId))
            .containsEntry("message_key", String.valueOf(orderId))
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 0)
            .containsEntry("payload", expectedPayload(orderId)));
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(payment(orderId)).isEqualTo(paymentBefore);
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(server.createCalls()).isEqualTo(createCallsBefore);
    }

    @Test
    @DisplayName("같은 payload의 Outbox 행이 이미 있으면 ALREADY_SAME이고 행이 하나도 바뀌지 않는다")
    void samePayloadIsLeftAlone() throws Exception {
        long orderId = confirmedWithOutbox();
        List<Map<String, Object>> before = rows(orderId);

        List<Outcome> outcomes = orderOutboxBackfill.ensure(List.of(orderId));

        assertThat(outcomes).containsExactly(new Outcome(orderId, Result.ALREADY_SAME));
        assertThat(rows(orderId)).isEqualTo(before);
    }

    @Test
    @DisplayName("이미 있는 Outbox 행의 payload가 지금 주문으로 만든 것과 다르면 PAYLOAD_MISMATCH이고 행을 덮어쓰지 않는다")
    void differentPayloadIsReportedNotOverwritten() throws Exception {
        long orderId = confirmedWithOutbox();
        jdbc.update("update order_outbox set payload = '{\"tampered\":true}' where order_id = ?", orderId);
        List<Map<String, Object>> before = rows(orderId);

        List<Outcome> outcomes = orderOutboxBackfill.ensure(List.of(orderId));

        assertThat(outcomes).containsExactly(new Outcome(orderId, Result.PAYLOAD_MISMATCH));
        assertThat(rows(orderId)).isEqualTo(before);
    }

    @Test
    @DisplayName("CONFIRMED가 아닌 주문과 없는 주문은 추측해 채우지 않고 NOT_CONFIRMED·ORDER_NOT_FOUND로 돌려주며 Outbox 행을 만들지 않는다")
    void unconfirmedAndMissingOrdersAreReported() {
        long draftId = newOrder();
        long missingId = draftId + 1000;

        List<Outcome> outcomes = orderOutboxBackfill.ensure(List.of(draftId, missingId));

        assertThat(outcomes).containsExactly(new Outcome(draftId, Result.NOT_CONFIRMED), new Outcome(missingId, Result.ORDER_NOT_FOUND));
        assertThat(jdbc.queryForObject("select count(*) from order_outbox", Long.class)).isZero();
        assertThat(orderStatus(draftId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("같은 주문을 두 번 복구하면 처음은 ADDED, 다음은 ALREADY_SAME이고 Outbox 행은 하나다")
    void secondRunKeepsOneRow() throws Exception {
        long orderId = confirmedWithoutOutbox();

        List<Outcome> first = orderOutboxBackfill.ensure(List.of(orderId));
        List<Map<String, Object>> afterFirst = rows(orderId);
        List<Outcome> second = orderOutboxBackfill.ensure(List.of(orderId));

        assertThat(first).containsExactly(new Outcome(orderId, Result.ADDED));
        assertThat(second).containsExactly(new Outcome(orderId, Result.ALREADY_SAME));
        assertThat(rows(orderId)).hasSize(1).isEqualTo(afterFirst);
    }

    private long confirmedWithoutOutbox() throws Exception {
        failingOutbox.skipAppends();
        long orderId = confirmedWithOutbox();
        failingOutbox.disarm();
        assertThat(rows(orderId)).isEmpty();
        return orderId;
    }

    private long confirmedWithOutbox() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        return orderId;
    }

    private String expectedPayload(long orderId) {
        return OrderConfirmedMessageSerializer.serialize(OrderConfirmedPayloadFactory.create(orderRepository.findById(orderId).orElseThrow()));
    }

    private List<Map<String, Object>> rows(long orderId) {
        return jdbc.queryForList("select id, event_id, message_key, status, attempt_count, row_failure_count, next_attempt_at,"
            + " payload, created_at from order_outbox where order_id = ? order by id", orderId);
    }

    private Map<String, Object> payment(long orderId) {
        return jdbc.queryForMap("select id, result, provider_status, attempt_key from payment_records where order_id = ?", orderId);
    }
}
