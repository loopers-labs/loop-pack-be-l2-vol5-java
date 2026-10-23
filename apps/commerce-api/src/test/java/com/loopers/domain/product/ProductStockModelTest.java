package com.loopers.domain.product;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AG-03 포함 개념 현재 재고와 원장 행. INV-03, DR-38. */
class ProductStockModelTest {

    @DisplayName("[DR-38] 새 재고 행은 0 이다.")
    @Test
    void create_startsAtZero() {
        ProductStockModel stock = new ProductStockModel(7L);

        assertThat(stock.getProductId()).isEqualTo(7L);
        assertThat(stock.getAvailable()).isZero();
    }

    @DisplayName("[INV-03][DR-38] 재고 설정은 최종 수량 설정이고, 차이만큼 ADJUSTMENT 행을 만든다.")
    @Test
    void adjustTo_setsFinalQuantity_andRecordsDelta() {
        ProductStockModel stock = stockOf(7L, 5);

        ProductStockLedgerModel up = stock.adjustTo(30).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(30);
        assertLedger(up, StockLedgerType.ADJUSTMENT, 25, null);

        ProductStockLedgerModel down = stock.adjustTo(10).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(10);
        assertLedger(down, StockLedgerType.ADJUSTMENT, -20, null);
    }

    @DisplayName("[DR-38] 같은 수량으로 설정하면 원장 행이 없다.")
    @Test
    void adjustTo_recordsNothing_whenSameQuantity() {
        ProductStockModel stock = stockOf(7L, 5);

        assertThat(stock.adjustTo(5)).isEmpty();
        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    @DisplayName("[INV-03][ER-21 INVALID_STOCK] 재고를 음수·null 로 설정하면 거부되고 기존 값 유지 (ASM-17).")
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void adjustTo_throwsInvalidStock_whenNegativeOrNull(Integer quantity) {
        ProductStockModel stock = stockOf(7L, 5);

        assertThrowsErrorType(() -> stock.adjustTo(quantity), ErrorType.INVALID_STOCK);

        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    @DisplayName("[INV-03][DR-38] 예약은 수량만큼 줄이고 음수 수량의 RESERVE 행을 만든다. 정확히 재고만큼도 가능하다.")
    @Test
    void reserve_reducesAvailable_andRecordsReserve() {
        ProductStockModel stock = stockOf(7L, 5);

        ProductStockLedgerModel entry = stock.reserve(100L, 5);

        assertThat(stock.getAvailable()).isZero();
        assertLedger(entry, StockLedgerType.RESERVE, -5, 100L);
    }

    @DisplayName("[INV-03][ER-16 INSUFFICIENT_STOCK] 재고 < 수량이면 거부되고 재고 유지. message 에 productId.")
    @Test
    void reserve_throwsInsufficientStock_whenNotEnough() {
        ProductStockModel stock = stockOf(7L, 5);

        assertThrowsErrorType(() -> stock.reserve(100L, 6), ErrorType.INSUFFICIENT_STOCK);
        assertThatThrownBy(() -> stock.reserve(100L, 6)).hasMessageContaining("productId = 7");

        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    @DisplayName("[INV-07][ER-13 INVALID_QUANTITY] 예약 수량이 0 이하면 거부되고 재고 유지.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void reserve_throwsInvalidQuantity_whenNotPositive(int quantity) {
        ProductStockModel stock = stockOf(7L, 5);

        assertThrowsErrorType(() -> stock.reserve(100L, quantity), ErrorType.INVALID_QUANTITY);

        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    @DisplayName("[DR-38] 반환은 수량만큼 늘리고 양수 수량의 RELEASE 행을 만든다 (결제 실패).")
    @Test
    void release_increasesAvailable_andRecordsRelease() {
        ProductStockModel stock = stockOf(7L, 5);
        stock.reserve(100L, 3);

        ProductStockLedgerModel entry = stock.release(100L, 3);

        assertThat(stock.getAvailable()).isEqualTo(5);
        assertLedger(entry, StockLedgerType.RELEASE, 3, 100L);
    }

    @DisplayName("[INV-07][ER-13 INVALID_QUANTITY] 반환 수량이 0 이하면 거부되고 재고 유지.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void release_throwsInvalidQuantity_whenNotPositive(int quantity) {
        ProductStockModel stock = stockOf(7L, 5);

        assertThrowsErrorType(() -> stock.release(100L, quantity), ErrorType.INVALID_QUANTITY);

        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    private static ProductStockModel stockOf(Long productId, int quantity) {
        ProductStockModel stock = new ProductStockModel(productId);
        stock.adjustTo(quantity);
        return stock;
    }

    private static void assertLedger(ProductStockLedgerModel entry, StockLedgerType type, int quantity, Long orderId) {
        assertThat(entry.getProductId()).isEqualTo(7L);
        assertThat(entry.getType()).isEqualTo(type);
        assertThat(entry.getQuantity()).isEqualTo(quantity);
        assertThat(entry.getOrderId()).isEqualTo(orderId);
    }
}
