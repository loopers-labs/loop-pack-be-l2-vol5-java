package com.loopers.domain.order;

import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문을 조회할 때, ")
    @Nested
    class FindByIdAndUserId {
        @DisplayName("저장된 주문은, flush/clear 후 재조회해도 품목이 동일하게 조회된다.")
        @Test
        void returnsOrder_withItems_afterFlushAndClear() {
            // arrange
            OrderModel saved = orderRepository.save(new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L))));
            Long id = saved.getId();
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<OrderModel> result = orderRepository.findByIdAndUserId(id, 1L);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().getItems()).hasSize(1);
            assertThat(result.get().getTotalAmount()).isEqualTo(20_000L);
        }

        @DisplayName("다른 사용자의 주문id로 조회하면, 빈 결과를 반환한다.")
        @Test
        void returnsEmpty_whenOwnedByAnotherUser() {
            // arrange
            OrderModel saved = orderRepository.save(new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L))));

            // act
            Optional<OrderModel> result = orderRepository.findByIdAndUserId(saved.getId(), 999L);

            // assert
            assertThat(result).isEmpty();
        }
    }

    @DisplayName("PG 결제를 진행한 주문을 다룰 때, ")
    @Nested
    class PgPaymentOrder {
        @DisplayName("결제 대기 상태와 결제수단은, flush/clear 후 재조회해도 그대로다. 결제수단이 없는 DRAFT는 비어 있다.")
        @Test
        void persistsStatusAndPaymentMethod() {
            // arrange
            OrderModel draft = orderRepository.save(new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L))));
            OrderModel pending = orderRepository.save(new OrderModel(1L, List.of(new OrderItem(1L, 1, 5_000L))));
            pending.startPgPayment();
            entityManager.flush();
            entityManager.clear();

            // act
            OrderModel foundDraft = orderRepository.findById(draft.getId()).orElseThrow();
            OrderModel foundPending = orderRepository.findById(pending.getId()).orElseThrow();

            // assert
            assertThat(foundDraft.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(foundDraft.getPaymentMethod()).isNull();
            assertThat(foundPending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(foundPending.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        }

        @DisplayName("소유자를 모르는 잠금 조회는 주문 id로만 찾고, 없는 주문은 빈 결과다.")
        @Test
        void findsByIdForUpdate_regardlessOfOwner() {
            // arrange
            OrderModel saved = orderRepository.save(new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L))));
            entityManager.flush();
            entityManager.clear();

            // act & assert
            assertThat(orderRepository.findByIdForUpdate(saved.getId())).isPresent();
            assertThat(orderRepository.findByIdForUpdate(99_999L)).isEmpty();
        }
    }
}
