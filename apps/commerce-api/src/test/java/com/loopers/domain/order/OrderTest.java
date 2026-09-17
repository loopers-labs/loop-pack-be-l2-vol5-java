package com.loopers.domain.order;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    private static final Long USER_ID = 1L;
    private static final Long BAG_ID = 10L;
    private static final Long CAP_ID = 20L;

    // 가방 3,000원×2 + 모자 1,000원×1 = 7,000원
    private Order draftOrder() {
        return new Order(USER_ID, List.of(new OrderItem(BAG_ID, 2L, 3_000L), new OrderItem(CAP_ID, 1L, 1_000L)));
    }

    @DisplayName("주문을 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("여러 품목으로 생성하면, DRAFT 상태와 품목 합계가 저장되고 결제 정보는 비어 있다. (ORD-001)")
        @Test
        void createsDraftOrder_withTotalAmount() {
            // act
            Order order = draftOrder();

            // assert
            assertAll(
                () -> assertThat(order.getUserId()).isEqualTo(USER_ID),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getItems()).hasSize(2),
                () -> assertThat(order.getTotalAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAmount()).isNull(),
                () -> assertThat(order.getConfirmedAt()).isNull()
            );
        }

        @DisplayName("같은 상품 품목이 여러 개면, 수량을 합쳐 한 품목으로 만든다. (T-3)")
        @Test
        void mergesItems_whenSameProductAppearsTwice() {
            // act
            Order order = new Order(USER_ID, List.of(new OrderItem(BAG_ID, 3L, 3_000L), new OrderItem(BAG_ID, 3L, 3_000L)));

            // assert
            assertAll(
                () -> assertThat(order.getItems()).hasSize(1),
                () -> assertThat(order.getItems().get(0).getQuantity()).isEqualTo(6L),
                () -> assertThat(order.getTotalAmount()).isEqualTo(18_000L)
            );
        }

        @DisplayName("품목이 없거나 비어 있으면, INVALID_VALUE 예외가 발생한다.")
        @ParameterizedTest
        @NullAndEmptySource
        void throwsInvalidValue_whenItemsAreEmpty(List<OrderItem> items) {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Order(USER_ID, items));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("사용자 ID가 없으면, INVALID_VALUE 예외가 발생한다.")
        @Test
        void throwsInvalidValue_whenUserIdIsNull() {
            // act
            DomainException result = assertThrows(DomainException.class,
                () -> new Order(null, List.of(new OrderItem(BAG_ID, 1L, 3_000L))));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {

        @DisplayName("DRAFT 주문을 확정하면, CONFIRMED가 되고 합계를 결제액으로 기록한다. (ORD-005, T-1)")
        @Test
        void confirmsOrder_andRecordsPaidAmount() {
            // arrange
            Order order = draftOrder();

            // act
            order.confirm(Map.of(BAG_ID, 3_000L, CAP_ID, 1_000L));

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getTotalAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getConfirmedAt()).isNotNull()
            );
        }

        @DisplayName("생성 뒤 상품 가격이 바뀌었으면, 확정 시점 단가로 품목 금액·합계·결제액을 계산한다. (T-2)")
        @Test
        void usesCurrentPrices_whenPricesChangedAfterCreation() {
            // arrange
            Order order = draftOrder();

            // act
            order.confirm(Map.of(BAG_ID, 4_000L, CAP_ID, 1_000L));

            // assert
            assertAll(
                () -> assertThat(order.getItems())
                    .filteredOn(item -> item.getProductId().equals(BAG_ID))
                    .singleElement()
                    .satisfies(item -> assertThat(item.getUnitPrice()).isEqualTo(4_000L)),
                () -> assertThat(order.getTotalAmount()).isEqualTo(9_000L),
                () -> assertThat(order.getPaidAmount()).isEqualTo(9_000L)
            );
        }

        @DisplayName("이미 CONFIRMED인 주문을 다시 확정하면, CONFLICT 예외가 발생하고 결제 정보가 유지된다. (ORD-003)")
        @Test
        void throwsConflict_whenOrderIsAlreadyConfirmed() {
            // arrange
            Order order = draftOrder();
            order.confirm(Map.of(BAG_ID, 3_000L, CAP_ID, 1_000L));

            // act
            DomainException result = assertThrows(DomainException.class,
                () -> order.confirm(Map.of(BAG_ID, 5_000L, CAP_ID, 5_000L)));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.CONFLICT),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getPaidAmount()).isEqualTo(7_000L)
            );
        }

        @DisplayName("품목의 상품 가격을 확인할 수 없으면(삭제된 상품), CONFLICT 예외가 발생하고 DRAFT로 남는다. (ORD-002, P-10)")
        @Test
        void throwsConflict_whenProductOfItemIsUnavailable() {
            // arrange
            Order order = draftOrder();

            // act
            DomainException result = assertThrows(DomainException.class, () -> order.confirm(Map.of(BAG_ID, 3_000L)));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.CONFLICT),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getTotalAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAmount()).isNull()
            );
        }
    }

    @DisplayName("주문 소유자를 확인할 때, ")
    @Nested
    class Owner {

        @DisplayName("주문한 사용자면 true, 다른 사용자면 false다. (ORD-003)")
        @Test
        void returnsWhetherUserOwnsOrder() {
            // arrange
            Order order = draftOrder();

            // act & assert
            assertAll(
                () -> assertThat(order.isOwnedBy(USER_ID)).isTrue(),
                () -> assertThat(order.isOwnedBy(2L)).isFalse()
            );
        }
    }
}
