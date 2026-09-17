package com.loopers.domain.product;

/**
 * 재고 수량의 변경 전후 값과 변경량을 담는 불변 Value Object.
 * changedQuantity 는 변경량의 크기이며 증감 방향은 before/after 로 판단한다.
 */
public record StockChange(long beforeQuantity, long afterQuantity, long changedQuantity) {
}
