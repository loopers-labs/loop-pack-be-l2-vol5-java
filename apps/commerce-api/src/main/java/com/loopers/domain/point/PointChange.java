package com.loopers.domain.point;

/**
 * 포인트 잔액의 변경 전후 값과 변경량을 담는 불변 Value Object.
 */
public record PointChange(long beforeBalance, long afterBalance, long changedAmount) {
}
