package com.loopers.domain.order;

import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order givenOrder(Long userId) {
        return orderJpaRepository.save(new Order(userId, List.of(new Order.OrderItemDraft(1L, 1, 1000L))));
    }

    @DisplayName("소유한 주문을 조회할 때,")
    @Nested
    class GetOwnedOrder {
        @DisplayName("요청자가 소유자와 같으면, 주문을 반환한다.")
        @Test
        void returnsOrder_whenRequesterIsOwner() {
            // arrange
            Order order = givenOrder(1L);

            // act
            Order result = orderService.getOwnedOrder(order.getId(), 1L);

            // assert
            assertThat(result.getId()).isEqualTo(order.getId());
        }

        @DisplayName("요청자가 소유자와 다르면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Order order = givenOrder(1L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> orderService.getOwnedOrder(order.getId(), 2L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("존재하지 않는 주문이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenOrderDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> orderService.getOwnedOrder(999L, 1L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("소유자가 다른 경우와 존재하지 않는 경우, 같은 메시지의 예외가 발생한다.")
        @Test
        void throwsSameMessage_forWrongOwnerAndNonexistentOrder() {
            // arrange
            Order order = givenOrder(1L);

            // act
            CoreException wrongOwner = assertThrows(CoreException.class, () -> orderService.getOwnedOrder(order.getId(), 2L));
            CoreException notExists = assertThrows(CoreException.class, () -> orderService.getOwnedOrder(999L, 1L));

            // assert — 존재 여부를 응답으로 노출하지 않는다 (Week1 INV-001 패턴)
            assertThat(wrongOwner.getCustomMessage()).isEqualTo("[id = " + order.getId() + "] 주문을 찾을 수 없습니다.");
            assertThat(notExists.getCustomMessage()).isEqualTo("[id = 999] 주문을 찾을 수 없습니다.");
        }
    }

    @DisplayName("확정을 위해 소유한 주문을 잠가서 조회할 때,")
    @Nested
    class GetOwnedOrderForUpdate {
        @DisplayName("요청자가 소유자와 같으면, 주문을 반환한다.")
        @Test
        void returnsOrder_whenRequesterIsOwner() {
            // arrange
            Order order = givenOrder(1L);

            // act
            Order result = orderService.getOwnedOrderForUpdate(order.getId(), 1L);

            // assert
            assertThat(result.getId()).isEqualTo(order.getId());
        }

        @DisplayName("요청자가 소유자와 다르면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Order order = givenOrder(1L);

            // act
            CoreException result =
                assertThrows(CoreException.class, () -> orderService.getOwnedOrderForUpdate(order.getId(), 2L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("존재하지 않는 주문이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenOrderDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> orderService.getOwnedOrderForUpdate(999L, 1L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
