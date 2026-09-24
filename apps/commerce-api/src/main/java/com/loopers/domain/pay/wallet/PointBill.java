package com.loopers.domain.pay.wallet;

import com.loopers.domain.shared.Money;
import java.time.Instant;

// 포인트 충전/사용 기록
public final class PointBill {
    private final Long id;
    private final long userId;
    private final PointBillType type;
    private final Money amount;
    private final Long orderId;
    private final Instant createdAt;

    private PointBill(Long id, long userId, PointBillType type, long amount, Long orderId, Instant createdAt) {
        if (userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        if (type == PointBillType.CHARGE && orderId != null) {
            throw new IllegalArgumentException("충전 기록의 주문 ID는 없어야 합니다.");
        }
        if (type == PointBillType.USE && (orderId == null || orderId <= 0)) {
            throw new IllegalArgumentException("사용 기록의 주문 ID는 양수여야 합니다.");
        }
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.amount = Money.positive(amount);
        this.orderId = orderId;
        this.createdAt = createdAt;
    }

    // 충전 기록 생성
    public static PointBill charge(long userId, long amount) {
        return new PointBill(null, userId, PointBillType.CHARGE, amount, null, null);
    }

    // 사용 기록 생성
    public static PointBill use(long userId, long orderId, long amount) {
        return new PointBill(null, userId, PointBillType.USE, amount, orderId, null);
    }

    // 저장된 데이터로부터 복원
    public static PointBill restore(long id, long userId, PointBillType type, long amount, Long orderId,
                                    Instant createdAt) {
        if (id <= 0 || createdAt == null) {
            throw new IllegalArgumentException("저장된 포인트 기록 상태가 올바르지 않습니다.");
        }
        return new PointBill(id, userId, type, amount, orderId, createdAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public PointBillType getType() {
        return type;
    }

    public long getAmount() {
        return amount.getValue();
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
