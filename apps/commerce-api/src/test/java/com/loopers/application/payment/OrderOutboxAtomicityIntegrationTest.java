package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.OrderConfirmationClockConfig;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.domain.order.OrderConfirmedMessageEncoder;
import com.loopers.fixture.pg.TransactionSnapshot;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * O3: 실제 HTTP·로컬 PG fixture·결과 적용 TX·MySQL 에서 최초 확정 TX B 와 Outbox 기록의 원자성을 확인한다(설계 W7.2-2·W7.2-3).
 * 자동 relay 는 아직 없다. 실패 주입은 테스트 전용이며(encoder spy·기존 BEFORE_COMMIT probe·SQL 로 만든 기존 Outbox 행),
 * 외부 PG 의 SUCCESS 는 로컬 rollback 과 별개로 fixture 상태에서 따로 관찰한다. 결과는 응답 뒤 새 연결의 SQL 로 읽는다.
 */
@Timeout(30)
@DisplayName("최초 확정 TX B 는 Outbox 기록과 함께 commit·rollback 되고, 실패 뒤 같은 거래 재조회로 PG create 없이 회복한다.")
class OrderOutboxAtomicityIntegrationTest extends PaymentHttpTestSupport {

    /** 두 Clock 모두 나노초·서울 zone 을 넣어 각자의 UTC·마이크로초 정규화와 서로 다른 시각임을 확인한다. */
    private static final Clock CONFIRMATION_CLOCK = Clock.fixed(Instant.parse("2026-11-04T01:00:00.111111999Z"), ZoneId.of("Asia/Seoul"));
    private static final Clock OUTBOX_CLOCK = Clock.fixed(Instant.parse("2026-11-04T02:30:00.222222888Z"), ZoneId.of("Asia/Seoul"));

    @TestBean(name = OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK, methodName = "confirmationClock")
    private Clock orderConfirmationClock;
    @TestBean(name = OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, methodName = "outboxClock")
    private Clock outboxRecordClock;
    @MockitoSpyBean
    private OrderConfirmedMessageEncoder encoder;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;

    static Clock confirmationClock() {
        return CONFIRMATION_CLOCK;
    }

    static Clock outboxClock() {
        return OUTBOX_CLOCK;
    }

    private List<Map<String, Object>> outbox() {
        return jdbc.queryForList("SELECT * FROM order_confirmed_outbox ORDER BY id");
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class, order);
    }

    /** TX A 가 commit 한 직전 상태: PAYMENT_PENDING·확정 시각 없음·결제 UNKNOWN·예약 1회·재고 차감·Outbox 0. */
    private void assertTxAStateOnly() {
        assertAll(
            () -> assertThat(jdbc.queryForMap("SELECT status, paid_amount, confirmed_at FROM orders WHERE id = ?", order))
                .containsEntry("status", "PAYMENT_PENDING").containsEntry("paid_amount", null).containsEntry("confirmed_at", null),
            () -> assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null),
            () -> assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1L),
            () -> assertThat(histories(order, "ORDER_RELEASE")).isZero(),
            () -> assertThat(stock(product.getId())).isEqualTo(3L),
            () -> assertThat(outbox()).as("새 Outbox 없음").isEmpty(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_conflict", Long.class)).isZero()
        );
    }

    /** 외부 PG 에는 이 주문의 SUCCESS 거래 하나가 그대로 남고 create 는 한 번이다. */
    private TransactionSnapshot assertPgSuccessKept() {
        List<TransactionSnapshot> transactions = pg.state().transactions();
        assertAll(
            () -> assertThat(transactions).singleElement().satisfies(transaction -> {
                assertThat(transaction.orderId()).isEqualTo(external(order));
                assertThat(transaction.status()).isEqualTo(PaymentStatus.SUCCESS);
            }),
            () -> assertThat(pg.state().counts().createReceived()).isEqualTo(1L)
        );
        return transactions.get(0);
    }

    private void assertRecoveredOnce(ResponseEntity<JsonNode> recovery) {
        assertAll(
            () -> assertThat(recovery.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(detail(order).path("status").asText()).isEqualTo("CONFIRMED"),
            () -> assertThat(outbox()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("event_id", "order-confirmed:" + order).containsEntry("status", "PENDING")),
            () -> assertThat(histories(order, "ORDER_RESERVATION")).as("예약 추가 없음").isEqualTo(1L),
            () -> assertThat(pg.state().counts().createReceived()).as("회복에 PG create 추가 없음").isEqualTo(1L),
            () -> assertThat(pg.state().transactions()).hasSize(1)
        );
        noPointEffect();
    }

    @DisplayName("즉시 SUCCESS 는 확정·예약 소비·PG 성공 사실과 Outbox 한 행을 함께 commit 하고, 두 Clock 을 각각 UTC 마이크로초로 쓴다.")
    @Test
    void commitsConfirmationWithOutboxAndSeparateClocks() throws Exception {
        success(order);

        ResponseEntity<JsonNode> response = confirm(order);

        Map<String, Object> row = outbox().get(0);
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(payment(order)).containsEntry("status", "SUCCESS"),
            () -> assertThat(outbox()).hasSize(1),
            () -> assertThat(row).containsEntry("event_id", "order-confirmed:" + order).containsEntry("order_id", order)
                .containsEntry("event_type", "OrderConfirmed").containsEntry("schema_version", 1)
                .containsEntry("message_key", Long.toString(order)).containsEntry("status", "PENDING"),
            () -> assertThat(text("SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%d %H:%i:%s.%f') FROM orders WHERE id = ?"))
                .as("확정 시각: orderConfirmationClock 의 UTC 마이크로초").isEqualTo("2026-11-04 01:00:00.111111"),
            () -> assertThat(payload.path("confirmedAt").asText()).isEqualTo("2026-11-04T01:00:00.111111Z"),
            () -> assertThat(text("SELECT DATE_FORMAT(next_attempt_at, '%Y-%m-%d %H:%i:%s.%f') FROM order_confirmed_outbox WHERE order_id = ?"))
                .as("기록 시각: outboxRecordClock 의 UTC 마이크로초, confirmedAt 과 다름").isEqualTo("2026-11-04 02:30:00.222222"),
            () -> assertThat(payload.path("items").toString())
                .isEqualTo("[{\"productId\":" + product.getId() + ",\"quantity\":2}]"),
            () -> assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1L),
            () -> assertThat(stock(product.getId())).isEqualTo(3L)
        );
        noPointEffect();
    }

    @DisplayName("encoder 가 실패하면 TX B 전체가 rollback 되어 TX A 상태만 남고 PG SUCCESS 는 남으며, 대사로 같은 거래를 다시 적용하면 한 번 확정된다.")
    @Test
    void encoderFailureRollsBackAndReconcileRecovers() {
        doThrow(new CoreException(ErrorType.INTERNAL_ERROR, "직렬화 실패 주입")).doCallRealMethod().when(encoder).encode(any());
        success(order);

        ResponseEntity<JsonNode> failed = confirm(order);

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertTxAStateOnly();
        assertPgSuccessKept();
        assertRecoveredOnce(reconcile(order));
    }

    @DisplayName("Outbox 를 실제로 insert·flush 한 뒤 commit 직전에 실패하면 함께 rollback 되고, 콜백으로 같은 거래를 다시 적용하면 한 번 확정된다.")
    @Test
    void failureAfterOutboxInsertRollsBackAndCallbackRecovers() {
        List<Number> inTransaction = new ArrayList<>();
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            // 같은 TX 연결에서 insert 가 실제로 실행됐는지 확인한 뒤 실패를 주입한다. 실패한 TX 에서 더 조회하지 않는다.
            inTransaction.add((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id = ?")
                .setParameter(1, order).getSingleResult());
            throw new IllegalStateException("commit 직전 실패 주입");
        });
        success(order);

        ResponseEntity<JsonNode> failed = confirm(order);
        probe.clear();

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(inTransaction).as("TX 안에서는 Outbox insert 가 실행됨").singleElement()
            .satisfies(count -> assertThat(count.longValue()).isEqualTo(1L));
        assertTxAStateOnly();
        TransactionSnapshot kept = assertPgSuccessKept();
        assertRecoveredOnce(callback(order, kept.transactionKey(), "SUCCESS"));
    }

    @DisplayName("같은 eventId 의 Outbox 가 이미 있으면 uk_oco_event 위반으로 TX B 가 rollback 되고, 결제 충돌로 분류·기록하지 않는다.")
    @Test
    void outboxUniqueViolationIsNotPaymentConflict() {
        // 테스트 전용 기존 행. 정상 잠금 경로 밖의 쓰기를 흉내 낸 것이며 제품 복구 상태가 아니다.
        jdbc.update("""
            INSERT INTO order_confirmed_outbox (event_id, order_id, event_type, schema_version, message_key, payload, status,
                attempt_count, cycle_attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, 'OrderConfirmed', 1, ?, '{"fixture":true}', 'PENDING', 0, 0, NOW(6), NOW(6), NOW(6))
            """, "order-confirmed:" + order, order, Long.toString(order));
        List<Map<String, Object>> fixtureRows = outbox();
        success(order);

        ResponseEntity<JsonNode> failed = confirm(order);

        assertAll(
            () -> assertThat(failed.getStatusCode().value()).isEqualTo(500),
            () -> assertThat(outbox()).as("기존 행 그대로·새 행 없음").isEqualTo(fixtureRows),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_conflict", Long.class)).as("결제 충돌 기록 없음").isZero(),
            () -> assertThat(payment(order)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null)
                .doesNotContainEntry("verification_status", "CONFLICT"),
            () -> assertThat(jdbc.queryForMap("SELECT status, confirmed_at FROM orders WHERE id = ?", order))
                .containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(histories(order, "ORDER_RESERVATION")).isEqualTo(1L)
        );
        assertPgSuccessKept();
    }
}
