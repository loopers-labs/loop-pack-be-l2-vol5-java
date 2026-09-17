package com.loopers.domain.order;

import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class OrderServiceIntegrationTest {

    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_BUYER_ID = 2L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order saveOrder(Long userId) {
        return orderJpaRepository.save(new Order(userId, List.of(new OrderItem(10L, 2L, 3_000L), new OrderItem(20L, 1L, 1_000L))));
    }

    @DisplayName("관리자가 주문 상세를 조회할 때, ")
    @Nested
    class GetOrder {

        @DisplayName("구매자와 관계없이, flush·clear 후 다시 읽은 주문의 품목·금액·구매자가 저장한 값과 같다.")
        @Test
        void returnsStoredOrderWithItems_regardlessOfBuyer() {
            transactionTemplate.executeWithoutResult(status -> {
                // arrange
                Order saved = saveOrder(BUYER_ID);
                entityManager.flush();
                entityManager.clear();

                // act
                Order found = orderService.getOrder(saved.getId());

                // assert
                assertAll(
                    () -> assertThat(found).isNotSameAs(saved),
                    () -> assertThat(found.getUserId()).isEqualTo(BUYER_ID),
                    () -> assertThat(found.getStatus()).isEqualTo(OrderStatus.DRAFT),
                    () -> assertThat(found.getTotalAmount()).isEqualTo(7_000L),
                    () -> assertThat(found.getItems())
                        .extracting(OrderItem::getProductId, OrderItem::getQuantity, OrderItem::getUnitPrice)
                        .containsExactlyInAnyOrder(tuple(10L, 2L, 3_000L), tuple(20L, 1L, 1_000L))
                );
            });
        }

        @DisplayName("없는 주문이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenOrderDoesNotExist() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> orderService.getOrder(999999L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.NOT_FOUND);
        }
    }

    @DisplayName("관리자가 주문 목록을 조회할 때, ")
    @Nested
    class GetAllOrders {

        @DisplayName("구매자 필터가 없으면, 모든 구매자의 주문을 최신순으로 페이지 조회한다.")
        @Test
        void returnsAllOrdersInLatestOrder_whenUserIdIsNull() {
            // arrange
            Order first = saveOrder(BUYER_ID);
            Order second = saveOrder(OTHER_BUYER_ID);
            Order third = saveOrder(BUYER_ID);

            // act
            List<Order> firstPage = orderService.getAllOrders(null, new PageCondition(0, 2));
            List<Order> secondPage = orderService.getAllOrders(null, new PageCondition(1, 2));

            // assert
            assertAll(
                () -> assertThat(firstPage).extracting(Order::getId).containsExactly(third.getId(), second.getId()),
                () -> assertThat(secondPage).extracting(Order::getId).containsExactly(first.getId()),
                () -> assertThat(orderService.countAllOrders(null)).isEqualTo(3L)
            );
        }

        @DisplayName("구매자 필터가 있으면, 그 구매자의 주문만 조회한다.")
        @Test
        void returnsOnlyBuyersOrders_whenUserIdIsGiven() {
            // arrange
            Order mine = saveOrder(BUYER_ID);
            saveOrder(OTHER_BUYER_ID);

            // act
            List<Order> orders = orderService.getAllOrders(BUYER_ID, new PageCondition(0, 20));

            // assert
            assertAll(
                () -> assertThat(orders).extracting(Order::getId).containsExactly(mine.getId()),
                () -> assertThat(orderService.countAllOrders(BUYER_ID)).isEqualTo(1L)
            );
        }
    }
}
