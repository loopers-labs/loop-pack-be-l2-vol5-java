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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 고객의 주문(Aggregate root). 사용자는 id 로만 참조하고, 품목({@link OrderItem})은 함께 저장하고 함께 지운다.
 *
 * <p>주문은 항상 DRAFT 로 만들어지며 재고와 포인트는 건드리지 않는다. 품목에는 주문 당시의 상품명·단가를 복사해
 * 두고, 합계는 품목 금액의 합이다.
 */
@Getter
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_amount", nullable = false))
    private Money totalAmount;

    // 결제를 마치기(CONFIRMED) 전에는 비어 있다. 컬럼이 모두 null 이면 값 자체가 null 로 읽힌다.
    @Embedded
    @AttributeOverride(name = "paidAmount.amount", column = @Column(name = "paid_amount"))
    private PaymentResult paymentResult;

    // PG 성공으로 처음 CONFIRMED 가 된 시각. 이전 포인트 확정 주문과 확정 전 주문은 비어 있다.
    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    // 품목은 필요할 때만 읽는다. 트랜잭션 밖에서는 읽을 수 없으므로 application 이 Info 로 변환해 내보낸다.
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItem> items = new ArrayList<>();

    // JPA 가 사용하는 기본 생성자. 새 주문은 create() 로 만든다.
    protected Order() {}

    /**
     * 품목마다 상품의 이름·가격을 복사해 DRAFT 주문을 만든다.
     *
     * @param products 품목의 상품 id 로 찾을 수 있어야 한다. 없으면 호출하는 쪽의 오류다 (존재 확인은 application 이 먼저 한다).
     */
    public static Order create(Long userId, OrderLines lines, Map<Long, OrderProduct> products) {
        if (userId == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "주문자는 필수입니다.");
        }
        List<OrderItem> items = new ArrayList<>();
        Money total = new Money(0);
        for (OrderLine line : lines.values()) {
            OrderProduct product = products.get(line.productId());
            if (product == null) {
                throw new CoreException(
                    ErrorType.INTERNAL_ERROR, "주문 품목의 상품 정보가 없습니다. (상품 ID: " + line.productId() + ")"
                );
            }
            OrderItem item = new OrderItem(line.productId(), product.name(), line.quantity(), product.price());
            items.add(item);
            total = total.plus(item.getAmount());
        }
        Order order = new Order();
        order.userId = userId;
        order.status = OrderStatus.DRAFT;
        order.totalAmount = total;
        order.items = items;
        return order;
    }

    /** 품목의 상품 id 를 품목 순서대로 돌려준다. */
    public List<Long> productIds() {
        return items.stream().map(OrderItem::getProductId).toList();
    }

    /** 확정할 수 있는 상태(DRAFT)인지 확인한다. 이미 확정된 주문이면 ORDER_ALREADY_CONFIRMED 로 거절한다. */
    public void ensureConfirmable() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.ORDER_ALREADY_CONFIRMED);
        }
    }

    /**
     * 결제 결과를 저장하고 CONFIRMED 로 바꾼다. 재고·포인트 차감은 호출하는 쪽(application)이 먼저 한다.
     *
     * <p>여기서 상태를 다시 검증하는 것은 불변식 보호용이다. 어디서 호출하든 확정된 주문의 결제 결과를 덮어쓰지 못하게 한다.
     * application 이 앞서 {@link #ensureConfirmable()} 을 부르는 것은 오류 우선순위용이라 역할이 다르다.
     * 거절되면 상태와 결제 결과는 그대로다.
     */
    public void confirm(PaymentResult paymentResult) {
        ensureConfirmable();
        if (paymentResult == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "결제 결과는 필수입니다.");
        }
        this.paymentResult = paymentResult;
        this.status = OrderStatus.CONFIRMED;
    }

    /**
     * 상태만 PAYMENT_PENDING(결제 대기)으로 바꾼다. PG 호출은 하지 않는다. DRAFT 에서만 바꿀 수 있다. 재고 확보와 시도
     * 기록은 호출하는 쪽(application)이 같은 트랜잭션에서 한다.
     *
     * <p>이미 CONFIRMED 인 주문은 기존 계약대로 ORDER_ALREADY_CONFIRMED 로, 그 밖의 진행 중·실패 주문은 CONFLICT 로
     * 거절한다. 이미 시도가 있는 주문에 같은 요청이 다시 오는 경우는 application 이 저장된 결과로 먼저 응답한다.
     */
    public void markPaymentPending() {
        if (status == OrderStatus.CONFIRMED) {
            throw new CoreException(ErrorType.ORDER_ALREADY_CONFIRMED);
        }
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "이미 결제가 진행된 주문입니다.");
        }
        this.status = OrderStatus.PAYMENT_PENDING;
    }

    /**
     * PG 성공을 확인해 결제 결과를 저장하고 CONFIRMED 로 바꾼다. PAYMENT_PENDING 에서만 가능하다. 재고는 시작할 때 이미
     * 차감했으므로 건드리지 않는다. 확정 시각은 이 최초 전이에서만 정해진다. 거절되면 상태와 결제 결과는 그대로다.
     */
    public void completePayment(PaymentResult paymentResult, ZonedDateTime confirmedAt) {
        requirePaymentPending();
        if (paymentResult == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "결제 결과는 필수입니다.");
        }
        if (confirmedAt == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "확정 시각은 필수입니다.");
        }
        this.paymentResult = paymentResult;
        this.confirmedAt = confirmedAt;
        this.status = OrderStatus.CONFIRMED;
    }

    /** PG 결제의 확정 실패를 반영해 PAYMENT_FAILED 로 바꾼다. PAYMENT_PENDING 에서만 가능하다. 재고 반환은 호출하는 쪽이 한다. */
    public void failPayment() {
        requirePaymentPending();
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    private void requirePaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new CoreException(ErrorType.CONFLICT, "결제 대기 중인 주문이 아닙니다.");
        }
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
