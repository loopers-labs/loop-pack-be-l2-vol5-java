package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AG-06 주문 (TB-06 orders, DR-10). 루트. 품목·단가·항목 금액·합계·결제액·결제 결과를 가진다.
 * INV-06·07·08·09·12 를 여기서 지킨다. 전이 메서드는 confirm() 하나 (ST-03).
 * 인덱스 IX-06 (내 주문 목록, 구매자 묶음).
 */
@Entity
@Table(name = "orders", indexes = @Index(name = "ix_orders_user_created", columnList = "user_id, created_at, id"))
public class OrderModel extends BaseEntity {

    /** 주문 생성 입력 한 줄. 단가는 Facade 가 카탈로그 BC 에서 받아 넘긴다 (설계 5-1). */
    public record Line(Long productId, Integer quantity, Long unitPrice) {
    }

    /** 참조 개념 사용자 (BC-01). 구매자 = 주문을 만든 사용자 (DR-09). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;

    /** 합계. 생성 시 1회 계산해 저장, 이후 불변 (DR-13). */
    @Column(name = "total_amount", nullable = false, updatable = false)
    private Long totalAmount;

    /** 결제액. CONFIRMED 이면 = totalAmount (INV-09). DRAFT 에는 없다. */
    @Column(name = "paid_amount")
    private Long paidAmount;

    /** 결제 결과: 차감이 이루어진 시각 (ASM-13, DR-13). */
    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemModel> items = new ArrayList<>();

    protected OrderModel() {}

    private OrderModel(Long userId) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
    }

    /**
     * FR-ORDER-01. ST-03 (없음) → DRAFT.
     * INV-12 품목 1개 이상 (ER-12) / INV-07 수량 > 0 (ER-13) / INV-08 같은 상품은 합산해 하나 (ASM-11)
     * / INV-06 항목 금액·합계 계산, 표현 범위 초과는 ER-14 (ASM-05).
     */
    public static OrderModel create(Long userId, List<Line> lines) {
        if (userId == null) {
            throw new CoreException(ErrorType.USER_NOT_FOUND, "주문의 사용자가 지정되지 않았습니다.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new CoreException(ErrorType.EMPTY_ORDER_ITEMS, "주문 품목이 없습니다.");
        }

        Map<Long, Line> merged = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.productId() == null) {
                throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, "상품이 지정되지 않은 품목이 있습니다.");
            }
            if (line.quantity() == null || line.quantity() <= 0) {
                throw new CoreException(ErrorType.INVALID_QUANTITY,
                    "수량은 양의 정수여야 합니다. [productId = " + line.productId() + ", quantity = " + line.quantity() + "]");
            }
            if (line.unitPrice() == null || line.unitPrice() < 0) {
                throw new CoreException(ErrorType.INVALID_PRODUCT_PRICE,
                    "단가가 올바르지 않습니다. [productId = " + line.productId() + ", unitPrice = " + line.unitPrice() + "]");
            }
            merged.merge(line.productId(), line, (a, b) -> {
                try {
                    return new Line(a.productId(), Math.addExact(a.quantity(), b.quantity()), a.unitPrice());
                } catch (ArithmeticException e) {
                    throw new CoreException(ErrorType.INVALID_QUANTITY,
                        "합산 수량이 표현 범위를 초과합니다. [productId = " + a.productId() + "]");
                }
            });
        }

        OrderModel order = new OrderModel(userId);
        long total = 0L;
        for (Line line : merged.values()) {
            try {
                long lineAmount = Math.multiplyExact(line.unitPrice(), (long) line.quantity());
                total = Math.addExact(total, lineAmount);
            } catch (ArithmeticException e) {
                throw new CoreException(ErrorType.AMOUNT_OUT_OF_RANGE,
                    "주문 금액이 표현 범위를 초과합니다. [productId = " + line.productId() + "]");
            }
            order.items.add(new OrderItemModel(order, line.productId(), line.quantity(), line.unitPrice()));
        }
        order.totalAmount = total;
        return order;
    }

    /** FR-ORDER-02 사전 조건 "DRAFT". 아니면 ER-15 ORDER_NOT_DRAFT (CONFIRMED 유지). */
    public void ensureDraft() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.ORDER_NOT_DRAFT, "[id = " + getId() + "] 확정할 수 없는 주문입니다. [status = " + status + "]");
        }
    }

    /** ST-03 DRAFT → CONFIRMED. 결제액 = 합계 (INV-09), 결제 결과 = 확정 시각 (ASM-13). 재고·잔액 차감은 Facade 가 같은 트랜잭션에서 (DR-08). */
    public void confirm() {
        ensureDraft();
        this.paidAmount = this.totalAmount;
        this.confirmedAt = ZonedDateTime.now();
        this.status = OrderStatus.CONFIRMED;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public List<OrderItemModel> getItems() {
        return Collections.unmodifiableList(items);
    }
}
