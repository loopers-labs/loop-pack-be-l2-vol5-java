package com.loopers.domain.ranking.model.vo;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.math.BigDecimal;
import java.util.UUID;

public record WeeklyPurchaseScore(UUID snapshotId, long productId, long score) {
    public WeeklyPurchaseScore {
        if (snapshotId == null || productId <= 0 || score <= 0) {
            throw new SnapshotIntegrityException("상품 점수의 snapshot·상품·양수 계약이 다릅니다.");
        }
    }

    public static WeeklyPurchaseScore fromAggregate(UUID snapshotId, long productId, BigDecimal total, long count, int minimum) {
        if (count <= 0 || minimum <= 0 || total == null) {
            throw new SnapshotIntegrityException("상품의 고정 입력이 없거나 수량이 양수가 아닙니다: " + productId);
        }
        try {
            return new WeeklyPurchaseScore(snapshotId, productId, total.longValueExact());
        } catch (ArithmeticException exception) {
            throw new SnapshotIntegrityException("상품 전체 수량이 정수 long 범위를 벗어났습니다: " + productId);
        }
    }
}
