package com.loopers.outbox.adapter.out.persistence;

import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 Outbox 저장 (docs/week7/plan.md D-0) · relay가 고를 행 조회 (D-1).
 * SENDING·PUBLISHED·BLOCKED 행을 고르는/거르는 사례는 상태 전이(학습자, 핵심 1)가 생긴 뒤 이 클래스에 더한다.
 */
@SpringBootTest
class OrderOutboxPersistenceAdapterIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    private static final String PAYLOAD = "{\"eventId\":\"e-1\",\"orderId\":1}";

    @Autowired
    private OrderOutboxPort orderOutboxPort;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private OrderOutbox pending(String eventId, long orderId, ZonedDateTime now) {
        return OrderOutbox.pending(eventId, orderId, "OrderConfirmed", 1, PAYLOAD, now);
    }

    @DisplayName("D-0 새 행은 PENDING · 시도 0 · 바로 보낼 차례로 저장되고, 고정 필드가 그대로 남는다.")
    @Test
    void savesPendingWithFixedFields() {
        // act
        orderOutboxPort.save(pending("e-1", 1L, NOW));
        entityManager.clear();

        // assert
        OrderOutbox reloaded = orderOutboxPort.findByEventId("e-1").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getNextAttemptAt().toInstant()).isEqualTo(NOW.toInstant());
        assertThat(reloaded.getOrderId()).isEqualTo(1L);
        assertThat(reloaded.getMessageKey()).isEqualTo("1");
        assertThat(reloaded.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(reloaded.getSchemaVersion()).isEqualTo(1);
        assertThat(reloaded.getPayload()).isEqualTo(PAYLOAD);
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getPublishedPartition()).isNull();
        assertThat(reloaded.getPublishedOffset()).isNull();
    }

    @DisplayName("ADR-W6-01 같은 eventId는 두 번 저장되지 않는다 (event_id unique).")
    @Test
    void eventIdIsUnique() {
        // arrange
        orderOutboxPort.save(pending("e-1", 1L, NOW));

        // act & assert
        assertThatThrownBy(() -> orderOutboxPort.save(pending("e-1", 2L, NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("D-1 차례가 된 행(next_attempt_at ≤ now)만 고르고, 시각이 아직 안 된 행은 고르지 않는다. 같은 시각도 차례다.")
    @Test
    void findDueSkipsFutureRows() {
        // arrange
        orderOutboxPort.save(pending("past", 1L, NOW.minusSeconds(1)));
        orderOutboxPort.save(pending("exact", 2L, NOW));
        orderOutboxPort.save(pending("future", 3L, NOW.plusSeconds(1)));

        // act
        List<OrderOutbox> due = orderOutboxPort.findDue(NOW, 10);

        // assert
        assertThat(due).extracting(OrderOutbox::getEventId).containsExactly("past", "exact");
    }

    @DisplayName("D-1 id 순서로 최대 limit개만 고른다 — next_attempt_at 순서가 아니라 저장 순서다.")
    @Test
    void findDueOrdersByIdAndLimits() {
        // arrange — 나중에 저장한 행의 시각이 더 이르다
        orderOutboxPort.save(pending("first", 1L, NOW.minusSeconds(1)));
        orderOutboxPort.save(pending("second", 2L, NOW.minusSeconds(5)));
        orderOutboxPort.save(pending("third", 3L, NOW.minusSeconds(9)));

        // act
        List<OrderOutbox> due = orderOutboxPort.findDue(NOW, 2);

        // assert
        assertThat(due).extracting(OrderOutbox::getEventId).containsExactly("first", "second");
    }
}
