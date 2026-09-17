package com.loopers.domain.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("StockHistory 는 재고 변경 결과와 원인을 기록한다.")
class StockHistoryModelTest {

    private static final long PRODUCT_ID = 3L;
    private static final long ORDER_ID = 77L;

    @DisplayName("관리자 변경 이력")
    @Nested
    class ChangedByAdmin {
        @DisplayName("변경 전후 수량과 관리자 변경 원인을 기록하고 주문 참조는 남기지 않는다.")
        @Test
        void recordsAdminChangeWithoutOrderReference() {
            StockChange change = new StockChange(5L, 2L, 3L);

            StockHistoryModel history = StockHistoryModel.changedByAdmin(PRODUCT_ID, change);

            assertAll(
                () -> assertThat(history.getProductId()).isEqualTo(PRODUCT_ID),
                () -> assertThat(history.getBeforeQuantity()).isEqualTo(5L),
                () -> assertThat(history.getAfterQuantity()).isEqualTo(2L),
                () -> assertThat(history.getChangedQuantity()).isEqualTo(3L),
                () -> assertThat(history.getCause()).isEqualTo(StockChangeCause.ADMIN_CHANGE),
                () -> assertThat(history.getOrderId()).isNull()
            );
        }
    }

    @DisplayName("주문 차감 이력")
    @Nested
    class DeductedByOrder {
        @DisplayName("변경 전후 수량과 주문 차감 원인, 원인이 된 주문 식별자를 기록한다.")
        @Test
        void recordsOrderDeductionWithOrderReference() {
            StockChange change = new StockChange(5L, 3L, 2L);

            StockHistoryModel history = StockHistoryModel.deductedByOrder(PRODUCT_ID, ORDER_ID, change);

            assertAll(
                () -> assertThat(history.getProductId()).isEqualTo(PRODUCT_ID),
                () -> assertThat(history.getBeforeQuantity()).isEqualTo(5L),
                () -> assertThat(history.getAfterQuantity()).isEqualTo(3L),
                () -> assertThat(history.getChangedQuantity()).isEqualTo(2L),
                () -> assertThat(history.getCause()).isEqualTo(StockChangeCause.ORDER_DEDUCTION),
                () -> assertThat(history.getOrderId()).isEqualTo(ORDER_ID)
            );
        }
    }
}
