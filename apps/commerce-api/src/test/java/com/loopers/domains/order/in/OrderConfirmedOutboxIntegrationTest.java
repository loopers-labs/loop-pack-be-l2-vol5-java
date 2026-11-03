package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domains.order.app.OrderConfirmedPayloadFactory;
import com.loopers.domains.order.app.PaymentReport;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OrderRepository;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.domains.order.out.OrderConfirmedOutboxPersistenceAdapter;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingOrderConfirmedOutbox;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 7주차 1단계: 최초 확정과 Outbox PENDING 기록이 한 트랜잭션이다. 결과는 서비스 트랜잭션이 끝난 뒤 JdbcTemplate 새 조회로 본다.
// 이 단계는 broker 전달(relay)과 무관하고, 프로세스 강제 종료·DB 연결 끊김은 흉내 내지 않는다(예외 경로의 rollback만 본다).
class OrderConfirmedOutboxIntegrationTest extends OrderEventsIntegrationSupport {
    private static final String CONTRACT_FIXTURE = "contracts/order-confirmed-v1.json";
    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-10-29T01:00:00");

    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderConfirmedOutboxPersistenceAdapter outboxAdapter;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("PG 성공으로 처음 확정하면 같은 트랜잭션에서 Outbox PENDING 1행이 남고 payload가 6주차 send payload와 같다")
    void firstConfirmationAppendsPendingOutbox() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        List<Map<String, Object>> rows = outboxRows(orderId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row)
            .containsEntry("event_id", eventId(orderId))
            .containsEntry("message_key", String.valueOf(orderId))
            .containsEntry("event_type", "OrderConfirmed")
            .containsEntry("schema_version", 1)
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 0)
            .containsEntry("created_at", CREATED_AT)
            .containsEntry("next_attempt_at", CREATED_AT)
            .containsEntry("published_at", null)
            .containsEntry("last_error", null);
        String payload = (String) row.get("payload");
        OrderConfirmedPayload stored = OrderConfirmedPayloadFactory.create(orderRepository.findById(orderId).orElseThrow());
        assertThat(payload).isEqualTo(OrderConfirmedMessageSerializer.serialize(stored));
        // D95로 6주차 직접 send가 아직 살아 있어 broker record와 비교한다. 2단계에서 직접 send를 없애면 relay가 보낸 record와의 비교로 바뀐다.
        ConsumerRecord<String, String> record = probe.awaitEvent(from, eventId(orderId));
        assertThat(OrderEventsProbe.json(payload)).isEqualTo(OrderEventsProbe.json(record));
        assertThat(OrderEventsProbe.json(payload)).isEqualTo(contractFixtureFor(orderId));
    }

    @ParameterizedTest(name = "Outbox 대역 {0}")
    @EnumSource(value = FailingOrderConfirmedOutbox.Failure.class, names = {"FAIL_BEFORE_INSERT", "FAIL_AFTER_INSERT"})
    @DisplayName("Outbox 저장이 실패하면 확정 전체가 rollback되어 주문·결제 기록·재고·Outbox가 직전 상태 그대로이고 PG create도 늘지 않는다")
    void outboxFailureRollsBackConfirmation(FailingOrderConfirmedOutbox.Failure failure) throws Exception {
        long orderId = newPendingOrder();
        Map<String, Object> paymentBefore = payment(orderId);
        long createCallsBefore = server.createCalls();
        failingOutbox.failOnNextAppend(failure);

        assertThatThrownBy(() -> applySuccess(orderId)).hasMessage(FailingOrderConfirmedOutbox.INJECTED_FAILURE);

        assertRolledBack(orderId, paymentBefore, createCallsBefore);
    }

    // 대역이 직렬화 예외를 던지는 장면이다. 운영 직렬화기 자체는 정적 함수라 바꿔 끼울 수 없어, 어댑터가 그 예외를 삼키지 않는지는 이 테스트가 증명하지 못한다.
    @Test
    @DisplayName("payload 직렬화가 실패하면 Outbox 저장 실패와 같이 확정 전체가 rollback된다")
    void serializeFailureRollsBackConfirmation() throws Exception {
        long orderId = newPendingOrder();
        Map<String, Object> paymentBefore = payment(orderId);
        long createCallsBefore = server.createCalls();
        failingOutbox.failOnNextAppend(FailingOrderConfirmedOutbox.Failure.FAIL_SERIALIZE);

        assertThatThrownBy(() -> applySuccess(orderId))
            .hasMessage(FailingOrderConfirmedOutbox.INJECTED_SERIALIZE_FAILURE);

        assertRolledBack(orderId, paymentBefore, createCallsBefore);
    }

    // 스케줄러가 시간 흐름에 따라 다시 조회하는 경로(reconcileDuePayments)는 보지 않는다.
    @ParameterizedTest(name = "재반영 경로 {0}")
    @ValueSource(strings = {"callback", "reconcile"})
    @DisplayName("Outbox 실패로 rollback된 뒤 같은 거래를 callback·reconcile로 다시 반영하면 확정·Outbox가 각 1이고 PG create는 늘지 않는다")
    void sameTransactionIsReappliedAfterOutboxFailure(String path) throws Exception {
        long orderId = newPendingOrder();
        failingOutbox.failOnNextAppend(FailingOrderConfirmedOutbox.Failure.FAIL_BEFORE_INSERT);
        assertThatThrownBy(() -> applySuccess(orderId)).hasMessage(FailingOrderConfirmedOutbox.INJECTED_FAILURE);
        long createCallsAfterFailure = server.createCalls();
        server.changeStatus(transactionKey(orderId), "SUCCESS");

        if (path.equals("callback")) {
            callback(orderId, "SUCCESS").andExpect(status().isOk());
        } else {
            reconcile(orderId).andExpect(status().isOk());
        }

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(outboxRows(orderId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("event_id", eventId(orderId))
            .containsEntry("message_key", String.valueOf(orderId))
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 0));
        assertThat(server.createCalls()).isEqualTo(createCallsAfterFailure);
    }

    @Test
    @DisplayName("같은 거래의 성공이 create 응답 → callback → reconcile → 같은 키 confirm으로 반복돼도 Outbox는 처음 기록한 1행이고 재고는 더 줄지 않는다")
    void repeatedSuccessAppendsOnce() throws Exception {
        long orderId = newOrder();
        String attemptKey = UUID.randomUUID().toString();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, attemptKey).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(5));

        callback(orderId, "SUCCESS").andExpect(status().isOk());
        reconcile(orderId).andExpect(status().isOk());
        confirm(orderId, attemptKey).andExpect(status().isOk());

        assertThat(outboxRows(orderId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("created_at", CREATED_AT)
            .containsEntry("status", "PENDING"));
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
    }

    @Test
    @DisplayName("결제를 시작하지 않은 DRAFT 주문에 성공 결과가 들어오면 DRAFT 그대로이고 Outbox 행이 없다")
    void draftResultAppendsNothing() {
        long orderId = newOrder();

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-DRAFT"));

        assertThat(orderStatus(orderId)).isEqualTo("DRAFT");
        assertThat(outboxRows(orderId)).isEmpty();
    }

    @ParameterizedTest(name = "create {0} → PG 상태 {1}")
    @CsvSource({"ACCEPT_PENDING, PENDING", "DELAY_AFTER_CREATE, UNKNOWN"})
    @DisplayName("결제 대기(PENDING·결과 모름)로 끝난 confirm은 Outbox 행을 남기지 않는다")
    void pendingAppendsNothing(CreateBehavior behavior, String providerStatus) throws Exception {
        long orderId = newOrder();
        server.createBehavior(behavior);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(payment(orderId)).containsEntry("provider_status", providerStatus);
        assertThat(outboxRows(orderId)).isEmpty();
    }

    @Test
    @DisplayName("PG 실패로 결제 실패가 된 주문은 Outbox 행이 없다")
    void failedAppendsNothing() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_FAILED);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(outboxRows(orderId)).isEmpty();
    }

    @Test
    @DisplayName("결제 만료 뒤 늦게 성공한 callback은 결제 만료 그대로이고 Outbox 행이 없다")
    void lateSuccessAfterExpiryAppendsNothing() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        clock.advance(Duration.ofMinutes(2));
        orders.expireOverduePayments();
        server.changeStatus(transactionKey(orderId), "SUCCESS");

        callback(orderId, "SUCCESS").andExpect(status().isOk());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(outboxRows(orderId)).isEmpty();
    }

    @Test
    @DisplayName("이관된 과거 POINT 확정 주문은 성공 결과·reconcile이 와도 Outbox 행이 없다(소급 기록 없음)")
    void legacyPointConfirmedOrderAppendsNothing() throws Exception {
        long orderId = newOrder();
        jdbc.update("update orders set status = 'CONFIRMED' where id = ?", orderId);
        jdbc.update("insert into payment_records(order_id, method, amount, result) values (?, 'POINT', 7000, 'SUCCESS')", orderId);

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, "TR-POINT"));
        reconcile(orderId).andExpect(status().isConflict());

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(outboxRows(orderId)).isEmpty();
    }

    // 테스트 스키마는 엔티티로 만든다. 운영 DDL SQL 파일의 제약은 이 테스트가 증명하지 못한다.
    @Test
    @DisplayName("같은 eventId로 Outbox를 두 번 기록하면 DB 유일 제약이 두 번째를 거절하고 행은 1개다")
    void duplicateEventIdIsRejectedByDatabase() {
        OrderConfirmedPayload payload = new OrderConfirmedPayload("1", eventId(1), 1, NOW,
            List.of(new OrderConfirmedPayload.Item(first, 2)));
        transactionTemplate.executeWithoutResult(status -> outboxAdapter.append(payload));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> outboxAdapter.append(payload)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from order_outbox where event_id = ?", Long.class, eventId(1)))
            .isEqualTo(1);
    }

    private long newPendingOrder() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        return orderId;
    }

    private void applySuccess(long orderId) {
        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, transactionKey(orderId)));
    }

    // 예약은 결제 시작 때 이미 줄었고 확정은 재고를 바꾸지 않으므로, 3/4는 "반환되지도 더 차감되지도 않았다"를 뜻한다.
    private void assertRolledBack(long orderId, Map<String, Object> paymentBefore, long createCallsBefore) {
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("select confirmed_at from orders where id = ?", LocalDateTime.class, orderId)).isNull();
        assertThat(payment(orderId)).isEqualTo(paymentBefore).containsEntry("result", "PENDING");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(outboxRows(orderId)).isEmpty();
        assertThat(server.createCalls()).isEqualTo(createCallsBefore);
        assertThat(publisher.sent()).isEmpty();
    }

    private List<Map<String, Object>> outboxRows(long orderId) {
        return jdbc.queryForList("select event_id, message_key, event_type, schema_version, payload, status, attempt_count,"
            + " next_attempt_at, last_error, created_at, published_at from order_outbox where order_id = ? order by id", orderId);
    }

    private Map<String, Object> payment(long orderId) {
        return jdbc.queryForMap("select result, provider_status from payment_records where order_id = ?", orderId);
    }

    // 공유 계약 fixture는 주문 42라, eventId·orderId·productId만 이 주문 값으로 바꾸면 나머지(키 집합·타입·확정 시각 형식)가 같아야 한다.
    private JsonNode contractFixtureFor(long orderId) throws IOException {
        JsonNode fixture;
        try (InputStream input = new ClassPathResource(CONTRACT_FIXTURE).getInputStream()) {
            fixture = OrderEventsProbe.json(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        ObjectNode expected = (ObjectNode) fixture;
        expected.put("eventId", eventId(orderId));
        expected.set("orderId", number(orderId));
        ArrayNode items = (ArrayNode) expected.get("items");
        ((ObjectNode) items.get(0)).set("productId", number(first));
        ((ObjectNode) items.get(1)).set("productId", number(second));
        return expected;
    }

    // 읽어 들인 payload와 같은 숫자 노드 종류(작은 값은 int)로 맞춘다.
    private static JsonNode number(long value) {
        return OrderEventsProbe.json(String.valueOf(value));
    }
}
