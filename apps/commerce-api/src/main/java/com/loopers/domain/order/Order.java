package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    /**
     * DRAFT → PAYMENT_PENDING (TX A) → CONFIRMED / PAYMENT_FAILED (TX B). CONFIRMED·PAYMENT_FAILED 는 종결이다.
     */
    public enum OrderStatus {
        DRAFT, PAYMENT_PENDING, CONFIRMED, PAYMENT_FAILED
    }

    /**
     * 무엇으로 결제했는가. 상태(어디까지 진행됐는가)와 다른 축이다.
     * 교체 전 POINT 로 확정한 주문은 POINT, PG 결제를 시작한 주문은 PG, 아직 결제하지 않은 DRAFT 는 null 이다.
     */
    public enum PaymentMethod {
        POINT, PG
    }

    private Long userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    /**
     * 확정 시점에 기록되는 결제액. 확정 전에는 0 이다.
     * PAYMENT_PENDING → CONFIRMED 전환과 결제액 기록은 OrderRepository.finishIfPaymentPending 의 조건부 UPDATE 가 맡는다.
     */
    private long paidAmount;

    /**
     * 최초 확정 시각 (6주차 설계 D2). finishIfPaymentPending 이 CONFIRMED 로 바꾸는 같은 UPDATE 에서 updatedAt 과 같은 값으로 한 번만 기록한다.
     * 이전 POINT 확정 주문과 확정 전 주문은 null 이다. 소급해서 채우지 않는다.
     */
    private ZonedDateTime confirmedAt;

    protected Order() {}

    public Order(Long userId, List<OrderItem> items) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문의 사용자 식별자는 필수입니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 하나 이상이어야 합니다.");
        }

        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = mergeSameProducts(items);
        this.paidAmount = 0L;
    }

    /**
     * 결제액은 생성 시점 단가로 계산하며, 확정 시에도 재계산하지 않는다.
     */
    public long getTotalAmount() {
        return items.stream()
            .mapToLong(OrderItem::getAmount)
            .sum();
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /**
     * TX A ②. 잠긴 행에서 DRAFT → PAYMENT_PENDING 으로 바꾸고 결제수단을 PG 로 기록한다.
     * DRAFT 가 아니면 결제를 시작할 수 없다. (CONFIRMED 주문의 "이미 확정" 판정은 PaymentStartService 가 먼저 한다.)
     */
    public void startPayment() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "결제를 시작할 수 없는 주문 상태입니다.");
        }
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentMethod = PaymentMethod.PG;
    }

    /**
     * 같은 상품이 여러 품목으로 들어오면 하나로 합산한다.
     */
    private List<OrderItem> mergeSameProducts(List<OrderItem> items) {
        Map<Long, OrderItem> merged = new LinkedHashMap<>();
        for (OrderItem item : items) {
            merged.merge(item.getProductId(), item,
                (existing, added) -> existing.mergeQuantity(added.getQuantity()));
        }
        return new ArrayList<>(merged.values());
    }
}
