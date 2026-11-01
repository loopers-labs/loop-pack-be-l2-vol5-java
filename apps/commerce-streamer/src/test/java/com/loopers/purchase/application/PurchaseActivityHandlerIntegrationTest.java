package com.loopers.purchase.application;

import com.loopers.purchase.application.event.OrderConfirmedEvent;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "demo-kafka.listener.auto-startup=false",
    "purchase-activity.kafka.listener.auto-startup=false"
})
@Import(MySqlTestContainersConfig.class)
@DisplayName("OrderConfirmed 구매 활동 소비 통합 테스트")
class PurchaseActivityHandlerIntegrationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-21T06:00:00Z");

    @Autowired private PurchaseActivityHandler purchaseActivityHandler;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearPurchaseActivities() {
        dropPurchaseActivityFailureConstraint();
        jdbcTemplate.update("delete from product_purchase_activity");
        jdbcTemplate.update("delete from processed_order_confirmation");
    }

    @AfterEach
    void removePurchaseActivityFailureConstraint() {
        dropPurchaseActivityFailureConstraint();
    }

    @Test
    @DisplayName("OrderConfirmed 이벤트를 받으면 구매 활동을 저장한다.")
    void storesPurchaseActivitiesWhenOrderConfirmedEventIsReceived() {
        OrderConfirmedEvent event = confirmedOrder("event-42", 42L, 7L, 2, 8L, 1);

        purchaseActivityHandler.consume(event);

        assertThat(readActivities(42L)).containsExactly(
            new PurchaseActivityRow(7L, 2, CONFIRMED_AT),
            new PurchaseActivityRow(8L, 1, CONFIRMED_AT)
        );
        assertThat(countProcessedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트가 재전달되면 정상 완료하고 구매 활동을 중복 저장하지 않는다.")
    void treatsSameEventAsIdempotentRedelivery() {
        OrderConfirmedEvent event = confirmedOrder("event-42", 42L, 7L, 2, 8L, 1);

        purchaseActivityHandler.consume(event);
        purchaseActivityHandler.consume(event);

        assertThat(readActivities(42L)).containsExactly(
            new PurchaseActivityRow(7L, 2, CONFIRMED_AT),
            new PurchaseActivityRow(8L, 1, CONFIRMED_AT)
        );
        assertThat(countProcessedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 eventId에 다른 주문 사실이 오면 충돌로 처리하고 추가 활동을 저장하지 않는다.")
    void rejectsEventIdCollisionWithoutAddingActivities() {
        purchaseActivityHandler.consume(confirmedOrder("event-42", 42L, 7L, 2, 8L, 1));
        OrderConfirmedEvent conflictingEvent = confirmedOrder("event-42", 42L, 7L, 3, 8L, 1);

        assertThatThrownBy(() -> purchaseActivityHandler.consume(conflictingEvent))
            .isInstanceOf(RuntimeException.class);

        assertThat(readActivities(42L)).containsExactly(
            new PurchaseActivityRow(7L, 2, CONFIRMED_AT),
            new PurchaseActivityRow(8L, 1, CONFIRMED_AT)
        );
        assertThat(countProcessedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 orderId에 다른 eventId의 주문 사실이 오면 충돌로 처리한다.")
    void rejectsOrderIdCollisionWithoutAddingActivities() {
        purchaseActivityHandler.consume(confirmedOrder("event-42", 42L, 7L, 2, 8L, 1));
        OrderConfirmedEvent conflictingEvent = confirmedOrder("event-42-reissued", 42L, 7L, 2, 8L, 1);

        assertThatThrownBy(() -> purchaseActivityHandler.consume(conflictingEvent))
            .isInstanceOf(RuntimeException.class);

        assertThat(readActivities(42L)).containsExactly(
            new PurchaseActivityRow(7L, 2, CONFIRMED_AT),
            new PurchaseActivityRow(8L, 1, CONFIRMED_AT)
        );
        assertThat(countProcessedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("품목 저장 중 실패하면 처리 기록과 모든 구매 활동을 rollback한다.")
    void rollsBackAllWritesWhenSavingAnItemFails() {
        jdbcTemplate.execute("""
            alter table product_purchase_activity
            add constraint ck_reject_product_8 check (product_id <> 8)
            """);
        OrderConfirmedEvent event = confirmedOrder("event-42", 42L, 7L, 2, 8L, 1);

        assertThatThrownBy(() -> purchaseActivityHandler.consume(event))
            .isInstanceOf(RuntimeException.class);

        assertThat(readActivities(42L)).isEmpty();
        assertThat(countProcessedEvents()).isZero();
    }

    private OrderConfirmedEvent confirmedOrder(
        String eventId,
        Long orderId,
        Long firstProductId,
        int firstQuantity,
        Long secondProductId,
        int secondQuantity
    ) {
        return new OrderConfirmedEvent(
            "OrderConfirmed",
            1,
            eventId,
            orderId,
            CONFIRMED_AT,
            List.of(
                new OrderConfirmedEvent.Item(firstProductId, firstQuantity),
                new OrderConfirmedEvent.Item(secondProductId, secondQuantity)
            )
        );
    }

    private List<PurchaseActivityRow> readActivities(Long orderId) {
        return jdbcTemplate.query(
            "select product_id, quantity, confirmed_at "
                + "from product_purchase_activity where order_id = ? order by product_id",
            (resultSet, rowNum) -> new PurchaseActivityRow(
                resultSet.getLong("product_id"),
                resultSet.getInt("quantity"),
                resultSet.getTimestamp("confirmed_at", Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()
            ),
            orderId
        );
    }

    private int countProcessedEvents() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from processed_order_confirmation", Integer.class);
        return count == null ? 0 : count;
    }

    private void dropPurchaseActivityFailureConstraint() {
        Integer count = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.table_constraints
            where constraint_schema = database()
              and table_name = 'product_purchase_activity'
              and constraint_name = 'ck_reject_product_8'
            """, Integer.class);
        if (count != null && count > 0) {
            jdbcTemplate.execute("alter table product_purchase_activity drop check ck_reject_product_8");
        }
    }

    private record PurchaseActivityRow(Long productId, int quantity, Instant confirmedAt) {
    }
}
