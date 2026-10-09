package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class OrderRepositoryIntegrationTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // DB 시각 정밀도와 무관하게 비교할 수 있도록 초 단위로 자름
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private Order saveDraft() {
        return orderRepository.save(Order.draft(USER_ID, List.of(
            new OrderLine(30L, "세 번째 상품", 3_000L, 1),
            new OrderLine(10L, "첫 번째 상품", 1_000L, 2),
            new OrderLine(30L, "세 번째 상품", 3_000L, 1)
        ), CREATED_AT));
    }

    private Order reload(Long orderId) {
        return transactionTemplate.execute(status -> {
            entityManager.clear();
            Order order = orderRepository.findByIdAndUserId(orderId, USER_ID).orElseThrow();
            order.getItems().size();
            return order;
        });
    }

    @DisplayName("저장한 주문을 다시 읽으면, 품목이 요청 순서(합산은 처음 위치)대로 스냅샷 · 만료 시각과 함께 읽힌다.")
    @Test
    void readsItemsInRequestOrder() {
        // arrange
        Order saved = saveDraft();

        // act
        Order found = reload(saved.getId());

        // assert
        assertAll(
            () -> assertThat(found.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(found.getTotalAmount()).isEqualTo(8_000L),
            () -> assertThat(found.getExpiresAt().toInstant()).isEqualTo(CREATED_AT.plusMinutes(30).toInstant()),
            () -> assertThat(found.getItems())
                .extracting(OrderItem::getProductId, OrderItem::getProductName, OrderItem::getUnitPrice, OrderItem::getQuantity)
                .containsExactly(tuple(30L, "세 번째 상품", 3_000L, 2), tuple(10L, "첫 번째 상품", 1_000L, 2))
        );
    }

    @DisplayName("확정한 주문의 상태 · 결제액 · 결제 시각이 다시 읽힌다.")
    @Test
    void readsConfirmedPayment() {
        // arrange
        Order saved = saveDraft();
        transactionTemplate.executeWithoutResult(status ->
            orderRepository.findByIdAndUserId(saved.getId(), USER_ID).orElseThrow().confirm(8_000L, ZonedDateTime.now()));

        // act
        Order found = reload(saved.getId());

        // assert
        assertAll(
            () -> assertThat(found.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(found.getPaymentAmount()).isEqualTo(8_000L),
            () -> assertThat(found.getPaidAt()).isNotNull()
        );
    }

    @DisplayName("소유자가 아닌 사용자로 조회하면, 주문이 없는 것과 같다.")
    @Test
    void hidesOthersOrder() {
        Order saved = saveDraft();

        assertThat(orderRepository.findByIdAndUserId(saved.getId(), 999L)).isEmpty();
    }
}
