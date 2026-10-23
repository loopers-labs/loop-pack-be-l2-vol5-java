package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("결제 실패 반환은 확보 수량을 현재 재고에 한 번 더하고 확보·반환 원인을 남긴다.")
class StockReleaseTest {

    @DisplayName("반환은 이전 값으로 되돌리지 않고 현재 수량에 더한다.")
    @Test
    void addsToCurrentQuantity() {
        ProductModel product = ProductModel.create(1L, "상품", 1_000L);
        product.changeStock(10L);
        product.decreaseStock(3L);
        product.changeStock(20L);

        StockChange change = product.releaseStock(3L);

        assertAll(
            () -> assertThat(product.getStockQuantity()).isEqualTo(23L),
            () -> assertThat(change).isEqualTo(new StockChange(20L, 23L, 3L))
        );
    }

    @DisplayName("삭제된 상품에도 반환하고 삭제 상태는 그대로 둔다.")
    @Test
    void releasesToDeletedProduct() {
        ProductModel product = ProductModel.create(1L, "상품", 1_000L);
        product.changeStock(5L);
        product.delete();

        product.releaseStock(2L);

        assertAll(
            () -> assertThat(product.getStockQuantity()).isEqualTo(7L),
            () -> assertThat(product.isDeleted()).isTrue()
        );
    }

    @DisplayName("1 미만 반환과 long 범위 초과는 내부 정합성 오류이고 수량을 바꾸지 않는다.")
    @Test
    void rejectsInvalidRelease() {
        ProductModel product = ProductModel.create(1L, "상품", 1_000L);
        product.changeStock(Long.MAX_VALUE);

        assertAll(
            () -> assertThatThrownBy(() -> product.releaseStock(0L))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> product.releaseStock(1L))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(product.getStockQuantity()).isEqualTo(Long.MAX_VALUE)
        );
    }

    @DisplayName("PG 결제의 확보·반환 이력은 각각 ORDER_RESERVATION·ORDER_RELEASE 원인과 주문 ID 를 갖는다.")
    @Test
    void recordsReservationAndRelease() {
        StockChange change = new StockChange(10L, 7L, 3L);

        StockHistoryModel reserved = StockHistoryModel.reservedByOrder(1L, 42L, change);
        StockHistoryModel released = StockHistoryModel.releasedByOrder(1L, 42L, change);

        assertAll(
            () -> assertThat(reserved.getCause()).isEqualTo(StockChangeCause.ORDER_RESERVATION),
            () -> assertThat(released.getCause()).isEqualTo(StockChangeCause.ORDER_RELEASE),
            () -> assertThat(reserved.getOrderId()).isEqualTo(42L),
            () -> assertThat(released.getOrderId()).isEqualTo(42L)
        );
    }
}
