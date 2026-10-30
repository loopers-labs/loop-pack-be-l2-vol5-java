package com.loopers.application.purchase;

import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PurchaseActivityHandlerTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T01:00:00Z");
    private static final String TOPIC = "order-events-handler-test";

    @Autowired
    private PurchaseActivityHandler purchaseActivityHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private PurchaseActivityTables tables;

    @BeforeEach
    void setUp() {
        tables = new PurchaseActivityTables(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 42(상품 7×2, 8×1)를 처리하면 완료 기록 1행과 품목별 구매 활동 2행이 확정 시각과 함께 남는다")
    @Test
    void recordsConfirmationAndActivities() {
        ConsumeResult result = purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));

        assertThat(result).isEqualTo(ConsumeResult.RECORDED);
        assertThat(tables.confirmations()).isEqualTo(1);
        List<Map<String, Object>> activities = tables.activitiesOf(42);
        assertThat(activities).hasSize(2);
        assertThat(activities.get(0)).containsEntry("product_id", 7L).containsEntry("quantity", 2);
        assertThat(activities.get(1)).containsEntry("product_id", 8L).containsEntry("quantity", 1);
        // DB에는 UTC로 저장한다(jpa.yml hibernate.jdbc.time_zone=UTC).
        assertThat(activities.get(0).get("confirmed_at")).isEqualTo(LocalDateTime.ofInstant(CONFIRMED_AT, ZoneOffset.UTC));
    }

    @DisplayName("같은 이벤트가 두 번 오면 두 번째는 아무것도 하지 않아 완료 기록 1행·구매 활동 2행·이상 기록 0행 그대로다")
    @Test
    void sameEventTwiceIsNoOp() {
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));

        ConsumeResult second = purchaseActivityHandler.consume(event("evt-42", 42, 8, 1, 7, 2), source(1));

        assertThat(second).isEqualTo(ConsumeResult.ALREADY_PROCESSED);
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activities()).isEqualTo(2);
        assertThat(tables.anomalies()).isZero();
    }

    @DisplayName("주문 43(상품 7×3)을 이어 처리하면 구매 활동이 3행이 되고 상품 7의 수량 합은 5다")
    @Test
    void anotherOrderAddsActivities() {
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));

        ConsumeResult result = purchaseActivityHandler.consume(event("evt-43", 43, 7, 3), source(1));

        assertThat(result).isEqualTo(ConsumeResult.RECORDED);
        assertThat(tables.confirmations()).isEqualTo(2);
        assertThat(tables.activities()).isEqualTo(3);
        assertThat(tables.quantityOf(7)).isEqualTo(5);
    }

    @DisplayName("같은 eventId로 내용(상품 7×5)이 다르게 오면 구매 활동을 바꾸지 않고 CONFLICT_SAME_EVENT_ID 이상 1행을 남기며, 그 record가 재전달돼도 이상은 1행이다")
    @Test
    void sameEventIdWithDifferentContentIsConflict() {
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));

        ConsumeResult result = purchaseActivityHandler.consume(event("evt-42", 42, 7, 5), source(1));
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 5), source(1));

        assertThat(result).isEqualTo(ConsumeResult.CONFLICT_SAME_EVENT_ID);
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activities()).isEqualTo(2);
        assertThat(tables.quantityOf(7)).isEqualTo(2);
        assertThat(tables.anomalyRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "CONFLICT_SAME_EVENT_ID")
                .containsEntry("event_id", "evt-42")
                .containsEntry("order_id", 42L)
                .containsEntry("topic", TOPIC)
                .containsEntry("partition_no", 0)
                .containsEntry("offset_no", 1L);
            assertThat(row.get("raw_payload")).isEqualTo("payload@1");
        });
    }

    @DisplayName("같은 orderId가 다른 eventId로 오면 구매 활동을 추가하지 않고 CONFLICT_SAME_ORDER 이상을 남긴다")
    @Test
    void sameOrderWithDifferentEventIdIsConflict() {
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));

        ConsumeResult result = purchaseActivityHandler.consume(event("evt-42-again", 42, 7, 2, 8, 1), source(1));

        assertThat(result).isEqualTo(ConsumeResult.CONFLICT_SAME_ORDER);
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activities()).isEqualTo(2);
        assertThat(tables.anomalyRows()).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "CONFLICT_SAME_ORDER")
            .containsEntry("event_id", "evt-42-again")
            .containsEntry("order_id", 42L));
    }

    static OrderConfirmedEvent event(String eventId, long orderId, long... productIdAndQuantity) {
        List<PurchasedItem> items = new ArrayList<PurchasedItem>();
        for (int index = 0; index < productIdAndQuantity.length; index += 2) {
            items.add(new PurchasedItem(productIdAndQuantity[index], (int) productIdAndQuantity[index + 1]));
        }
        return new OrderConfirmedEvent(eventId, orderId, CONFIRMED_AT, items);
    }

    static EventSource source(long offset) {
        return new EventSource(TOPIC, 0, offset, "payload@" + offset);
    }
}
