package com.loopers.domain.order;

import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OrderOutboxRepositoryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    @Autowired
    private OrderOutboxRepository orderOutboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static OrderConfirmedEvent eventOf(Long orderId) {
        return new OrderConfirmedEvent(
            OrderConfirmedEvent.EVENT_TYPE,
            OrderConfirmedEvent.SCHEMA_VERSION,
            OrderConfirmedEvent.eventIdOf(orderId),
            orderId,
            "2026-09-07T01:00:00Z",
            List.of(new OrderConfirmedEvent.Item(7L, 2))
        );
    }

    @DisplayName("저장한 행은 flush/clear 후에도 eventId로 찾고, payload·상태·기록 시각이 그대로 남는다.")
    @Test
    void findsByEventId_withPersistedValues() {
        // arrange
        String payload = "{\"eventId\":\"order-confirmed:v1:42\",\"items\":[{\"productId\":7,\"quantity\":2}]}";
        orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(42L), payload, RECORDED_AT));
        entityManager.flush();
        entityManager.clear();

        // act
        OrderOutboxModel found = orderOutboxRepository.findByEventId("order-confirmed:v1:42").orElseThrow();

        // assert
        assertThat(found.getOrderId()).isEqualTo(42L);
        assertThat(found.getMessageKey()).isEqualTo("42");
        assertThat(found.getPayload()).isEqualTo(payload);
        assertThat(found.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(found.getNextAttemptAt()).isEqualTo(RECORDED_AT);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(orderOutboxRepository.findByEventId("order-confirmed:v1:99")).isEmpty();
    }

    @DisplayName("같은 eventId의 두 번째 행은 DB 유일 제약이 막는다.")
    @Test
    void rejectsSecondRowOfSameEvent() {
        // arrange
        orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(42L), "{\"n\":1}", RECORDED_AT));
        entityManager.flush();

        // act & assert: 예외 종류는 flush 경로에 따라 달라서, 기존 저장소 테스트처럼 막은 제약 이름으로 확인한다
        assertThatThrownBy(() -> {
            orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(42L), "{\"n\":2}", RECORDED_AT));
            entityManager.flush();
        }).hasStackTraceContaining("uk_order_outbox_event");
        entityManager.clear();
    }

    @DisplayName("relay 대상은 재시도 시각이 된 PENDING만 (다음 시도 시각, id) 순으로 limit개까지다.")
    @Test
    void findsDuePendingInRelayOrder() {
        // arrange
        Instant now = Instant.parse("2026-09-07T02:00:00Z");
        orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(1L), "{}", now.minusSeconds(2)));
        orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(2L), "{}", now.minusSeconds(3)));
        orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(3L), "{}", now));
        orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(4L), "{}", now.plusSeconds(5)));
        OrderOutboxModel published = orderOutboxRepository.save(OrderOutboxModel.pending(eventOf(5L), "{}", now.minusSeconds(10)));
        published.markPublished(now);
        entityManager.flush();
        entityManager.clear();

        // act
        List<String> firstTwo = orderOutboxRepository.findDuePendingEventIds(now, 2);
        List<String> all = orderOutboxRepository.findDuePendingEventIds(now, 10);

        // assert
        assertThat(firstTwo).containsExactly("order-confirmed:v1:2", "order-confirmed:v1:1");
        assertThat(all).as("재시도 시각 전(4)과 PUBLISHED(5)는 고르지 않는다")
            .containsExactly("order-confirmed:v1:2", "order-confirmed:v1:1", "order-confirmed:v1:3");
    }

    @DisplayName("relay 조회용 인덱스는 상태, 다음 시도 시각, id 순서의 열을 갖는다.")
    @Test
    void createsRelayIndexInStatusNextAttemptIdOrder() {
        // act
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'order_outbox' AND index_name = 'idx_order_outbox_relay' ORDER BY seq_in_index",
            String.class
        );

        // assert
        assertThat(columns).containsExactly("status", "next_attempt_at", "id");
    }
}
