package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.EnumNameConverter;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

    /** 신규 enum 열은 native ENUM·값 CHECK 없는 VARCHAR 로 둔다(설계 3.6 열 타입과 ORM 매핑). */
    @Convert(converter = PaymentMethodConverter.class)
    @Column(name = "payment_method", length = 16)
    private PaymentMethod paymentMethod;

    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "total_amount", nullable = false))
    private Money totalAmount;

    @Column(name = "point_amount")
    private Long pointAmount;

    /** 실제로 성공한 결제액. PG 결제 대기·실패에서는 null 이다. */
    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "paid_amount"))
    private Money paidAmount;

    /**
     * 최초 PG 확정 전이에서 한 번 기록하는 내부 확정 시각. PG 승인 시각이나 commit 시각이 아니다.
     * 확정 전 PG 주문과 기존 POINT 주문은 null 이다. 확정 UPDATE 로 처음 기록하므로 updatable=false 를 쓰지 않는다.
     */
    @Column(name = "confirmed_at", columnDefinition = "DATETIME(6)")
    private ZonedDateTime confirmedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItemModel> items = new ArrayList<>();

    protected OrderModel() {}

    private OrderModel(Long userId, List<OrderItemModel> items) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = new ArrayList<>(items);
        this.totalAmount = calculateTotal();
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

    public void requireStoredItemsConsistent() {
        if (items.isEmpty() || items.stream().anyMatch(item ->
            item.getProductId() == null || item.getQuantity() < 1 || item.getUnitPrice() == null)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "저장된 주문 품목이 올바르지 않습니다.");
        }
        try {
            if (!calculateTotal().equals(totalAmount)) {
                throw new CoreException(ErrorType.INTERNAL_ERROR, "주문 총액이 고정 품목 합계와 다릅니다.");
            }
        } catch (CoreException failure) {
            if (failure.getErrorType() == ErrorType.NUMERIC_OVERFLOW) {
                throw new CoreException(ErrorType.INTERNAL_ERROR, "저장된 주문 품목 금액이 표현 범위를 넘습니다.");
            }
            throw failure;
        }
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
     * 기존 POINT 확정 경로이며 I4 에서 PG 결제로 바뀐다. 신규 PG 주문에는 호출하지 않는다(설계 3.6).
     */
    public void confirmWithPoints(long chargedPointAmount) {
        requireConfirmable();
        if (chargedPointAmount != this.totalAmount.toWon()) {
            throw new CoreException(
                ErrorType.INTERNAL_ERROR,
                "차감한 포인트가 주문 총액과 다릅니다. [totalAmount = " + totalAmount.toWon()
                    + ", usedPoint = " + chargedPointAmount + "]"
            );
        }

        this.paymentMethod = PaymentMethod.POINT;
        this.pointAmount = chargedPointAmount;
        this.paidAmount = Money.of(chargedPointAmount);
        this.status = OrderStatus.CONFIRMED;
    }

    /** DRAFT 의 결제 정보가 비어 있을 때만 PG 결제를 시작한다. 신규 PG 결제의 포인트 사용액은 0 이다. */
    public void startPgPayment() {
        requireConfirmable();
        if (paymentMethod != null || pointAmount != null || paidAmount != null || confirmedAt != null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "DRAFT 주문에 결제 정보가 이미 있습니다.");
        }
        this.paymentMethod = PaymentMethod.PG;
        this.pointAmount = 0L;
        this.status = OrderStatus.PAYMENT_PENDING;
    }

    /**
     * 검증한 PG 성공 금액이 주문 총액과 같을 때만 확정하고 확정 시각을 함께 기록한다. 결과 반영 TX 의 상태 검사 뒤에 호출한다.
     * 시각은 호출자가 정규화해 넘긴다. 형식이 다르면 보정하지 않고 거절하며, 모든 검사를 통과한 뒤에만 값을 바꾼다.
     */
    public void confirmPg(Money amount, ZonedDateTime confirmedAt) {
        requirePgPending();
        if (amount == null || !amount.equals(totalAmount)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 성공 금액이 주문 총액과 다릅니다.");
        }
        if (!isUtcMicros(confirmedAt)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 확정 시각이 UTC·마이크로초 값이 아닙니다.");
        }
        this.paidAmount = amount;
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }

    public void failPg() {
        requirePgPending();
        this.paidAmount = null;
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    /**
     * 확정 시각이 고정 UTC·마이크로초 값인지 본다. 겨울의 Europe/London 처럼 지금 offset 만 0 인 지역 zone 은
     * 정규화해도 UTC 가 아니므로 거절한다. null 은 false 이며, 상태에 따라 null 이어야 하는지는 호출자가 따로 본다.
     */
    public static boolean isUtcMicros(ZonedDateTime value) {
        return value != null
            && value.getZone().normalized().equals(ZoneOffset.UTC)
            && value.getNano() % 1_000 == 0;
    }

    public static class PaymentMethodConverter extends EnumNameConverter<PaymentMethod> {
        public PaymentMethodConverter() {
            super(PaymentMethod.class);
        }
    }

    /** 확정 시각이 남은 PG 대기 주문은 손상된 저장 상태로 보고 전이하지 않는다. */
    private void requirePgPending() {
        if (status != OrderStatus.PAYMENT_PENDING || paymentMethod != PaymentMethod.PG) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 결제 대기 주문이 아닙니다. [status = " + status + "]");
        }
        if (confirmedAt != null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확정 전 PG 주문에 확정 시각이 있습니다.");
        }
    }
}
