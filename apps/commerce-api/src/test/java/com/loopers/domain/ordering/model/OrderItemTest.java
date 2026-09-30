package com.loopers.domain.ordering.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderItemTest {

    @DisplayName("주문 품목 생성")
    @Nested
    class Create {
        @DisplayName("단가와 수량으로 품목 금액을 계산한다")
        @Test
        void computesAmount_fromUnitPriceAndQuantity() {
            OrderItem item = OrderItem.create(1L, "상품", 1_000L, 3);

            assertThat(item.getProductId()).isEqualTo(1L);
            assertThat(item.getProductName()).isEqualTo("상품");
            assertThat(item.getUnitPrice()).isEqualTo(1_000L);
            assertThat(item.getQuantity()).isEqualTo(3);
            assertThat(item.getAmount()).isEqualTo(3_000L);
        }

        @DisplayName("0 이하 수량은 거절한다")
        @Test
        void rejectsCreate_whenQuantityIsNotPositive() {
            assertThatThrownBy(() -> OrderItem.create(1L, "상품", 1_000L, 0))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INVALID_QUANTITY);
        }

        @DisplayName("금액 계산이 범위를 초과하면 거절한다")
        @Test
        void rejectsCreate_whenAmountOverflows() {
            assertThatThrownBy(() -> OrderItem.create(1L, "상품", Long.MAX_VALUE, 2))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
        }

        @DisplayName("0 이하 상품 ID는 거절한다")
        @Test
        void rejectsCreate_whenProductIdIsNotPositive() {
            assertThatThrownBy(() -> OrderItem.create(0L, "상품", 1_000L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("주문 품목 복원")
    @Nested
    class Restore {
        @DisplayName("저장된 금액이 단가·수량과 일치하면 복원한다")
        @Test
        void restoresItem_whenAmountMatches() {
            OrderItem item = OrderItem.restore(1L, "상품", 1_000L, 3, 3_000L);

            assertThat(item.getAmount()).isEqualTo(3_000L);
        }

        @DisplayName("저장된 금액이 단가·수량과 다르면 거절한다")
        @Test
        void rejectsRestore_whenAmountDoesNotMatch() {
            assertThatThrownBy(() -> OrderItem.restore(1L, "상품", 1_000L, 3, 2_999L))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
