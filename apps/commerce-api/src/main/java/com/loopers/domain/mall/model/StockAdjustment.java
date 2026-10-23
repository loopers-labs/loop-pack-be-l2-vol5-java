package com.loopers.domain.mall.model;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** 상품 수량을 직접 바꾸지 않는다. 실제 증감과 이 기록은 같은 업무 TX에서 저장한다. */
@Getter
public final class StockAdjustment {
    public enum State { DEDUCTED, CONSUMED, RESTORED }

    private final long orderId;
    private final List<Item> items;
    private State state;
    private final long version;
    private final Instant createdAt;

    private StockAdjustment(long orderId, List<Item> items, State state, long version, Instant createdAt) {
        if (orderId <= 0 || version < 0 || items == null || items.isEmpty()
                || items.stream().map(Item::productId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("주문별 재고 처리 품목이 올바르지 않습니다.");
        }
        this.orderId = orderId;
        this.items = items.stream().sorted(Comparator.comparingLong(Item::productId)).toList();
        this.state = Objects.requireNonNull(state);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static StockAdjustment create(long orderId, List<Item> items) {
        return new StockAdjustment(orderId, List.copyOf(items), State.DEDUCTED, 0,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public static StockAdjustment restore(long orderId, List<Item> items, State state, long version, Instant createdAt) {
        return new StockAdjustment(orderId, items, state, version, createdAt);
    }

    public boolean consume() {
        if (state == State.CONSUMED) {
            return false;
        }
        if (state != State.DEDUCTED) {
            throw new DomainException(DomainErrorCode.STOCK_ADJUSTMENT_ALREADY_FINISHED);
        }
        state = State.CONSUMED;
        return true;
    }

    public boolean restore() {
        if (state == State.RESTORED) {
            return false;
        }
        if (state != State.DEDUCTED) {
            throw new DomainException(DomainErrorCode.STOCK_ADJUSTMENT_ALREADY_FINISHED);
        }
        state = State.RESTORED;
        return true;
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0) {
                throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
            }
            if (quantity <= 0) {
                throw new DomainException(DomainErrorCode.INVALID_QUANTITY);
            }
        }
    }
}
