package com.loopers.domain.order;

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
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("확정 가능한 DRAFT 주문을 조회할 때, ")
    @Nested
    class GetDraftOrderOwnedBy {
        @DisplayName("본인 소유의 DRAFT 주문이면, 정상적으로 반환된다.")
        @Test
        void returnsOrder_whenOwnedAndDraft() {
            // arrange
            OrderModel order = orderService.createOrder(1L, List.of(new OrderItem(1L, 2, 10_000L)));

            // act
            OrderModel result = orderService.getDraftOrderOwnedBy(order.getId(), 1L);

            // assert
            assertThat(result.getId()).isEqualTo(order.getId());
        }

        @DisplayName("이미 확정된 주문이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenAlreadyConfirmed() {
            // arrange
            OrderModel order = orderService.createOrder(1L, List.of(new OrderItem(1L, 2, 10_000L)));
            orderService.confirmOrder(order, order.getTotalAmount());

            // act
            CoreException result = assertThrows(CoreException.class, () -> orderService.getDraftOrderOwnedBy(order.getId(), 1L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("본인 소유가 아니면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenOwnedByAnotherUser() {
            // arrange
            OrderModel order = orderService.createOrder(1L, List.of(new OrderItem(1L, 2, 10_000L)));

            // act
            CoreException result = assertThrows(CoreException.class, () -> orderService.getDraftOrderOwnedBy(order.getId(), 999L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
