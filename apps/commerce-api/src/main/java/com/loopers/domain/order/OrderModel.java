package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 주문 상태와 품목, 주문 총액·포인트 사용액·결제액을 구분해 관리한다.
 */
@Entity
@Table(name = "orders")
@Getter
public class OrderModel extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "order_total", nullable = false))
    private Money orderTotal;

    @Column(name = "used_point_amount")
    private Long usedPointAmount;

    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "payment_amount"))
    private Money paymentAmount;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItemModel> items = new ArrayList<>();

    protected OrderModel() {}

    private OrderModel(Long userId, List<OrderItemModel> items) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = new ArrayList<>(items);
        this.orderTotal = calculateTotal();
    }

    public static OrderModel draft(Long userId, List<OrderItemModel> items) {
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.INVALID_ORDER_ITEMS);
        }
        return new OrderModel(userId, items);
    }

    public Money calculateTotal() {
        Money total = Money.of(0L);
        for (OrderItemModel item : items) {
            total = total.add(item.calculateAmount());
        }
        return total;
    }

    public List<OrderItemModel> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** 다른 사용자의 주문은 존재 여부를 노출하지 않고 없는 주문으로 처리한다. */
    public void requireOwnedBy(Long requesterId) {
        if (!this.userId.equals(requesterId)) {
            throw new CoreException(ErrorType.ORDER_NOT_FOUND);
        }
    }

    public void requireConfirmable() {
        if (this.status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.ORDER_NOT_CONFIRMABLE);
        }
    }

    /**
     * 차감한 포인트가 주문 총액과 같은 금전적 가치인지 확인하고 결제 결과를 기록한다.
     */
    public void confirmWithPoints(long chargedPointAmount) {
        requireConfirmable();
        if (chargedPointAmount != this.orderTotal.toWon()) {
            throw new CoreException(
                ErrorType.INTERNAL_ERROR,
                "차감한 포인트가 주문 총액과 다릅니다. [orderTotal = " + orderTotal.toWon()
                    + ", usedPoint = " + chargedPointAmount + "]"
            );
        }

        this.usedPointAmount = chargedPointAmount;
        this.paymentAmount = Money.of(chargedPointAmount);
        this.status = OrderStatus.CONFIRMED;
    }
}
