package com.loopers.domain.ordering.model;

import com.loopers.domain.shared.Money;
import java.time.Instant;

// 주문 기록
public final class OrderRecord {
    private final Long id;
    private final long userId;
    private final Money amount;
    private final OrderRecordStatus status;
    private final Instant createdAt;

    private OrderRecord(Long id, long userId, long amount, OrderRecordStatus status, Instant createdAt) {
        if (userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        this.id = id;
        this.userId = userId;
        this.amount = Money.positive(amount);
        this.status = status;
        this.createdAt = createdAt;
    }

    // 결제 완료 상태로 생성
    public static OrderRecord paid(long userId, long amount) {
        return new OrderRecord(null, userId, amount, OrderRecordStatus.PAID, null);
    }

    // 저장된 데이터로부터 복원
    public static OrderRecord restore(long id, long userId, long amount, OrderRecordStatus status,
                                    Instant createdAt) {
        if (id <= 0 || createdAt == null) {
            throw new IllegalArgumentException("저장된 주문 기록 상태가 올바르지 않습니다.");
        }
        return new OrderRecord(id, userId, amount, status, createdAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public long getAmount() {
        return amount.getValue();
    }

    public OrderRecordStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
