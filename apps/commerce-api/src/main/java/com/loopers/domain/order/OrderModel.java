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
 * AG-06 주문 (TB-06 orders, DR-10). 루트. 품목·단가·항목 금액·합계·결제액·결제 결과·결제 시도 키·PG 거래 키를 가진다.
 * INV-06·07·08·09·12 를 여기서 지킨다. 전이 메서드는 startPayment()·confirm()·fail() (ST-03: DRAFT → AWAITING_PAYMENT → CONFIRMED | FAILED).
 * 인덱스 IX-06 (내 주문 목록, 구매자 묶음).
 */
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "ix_orders_user_created", columnList = "user_id, created_at, id"),
    @Index(name = "ix_orders_status_payment_started", columnList = "status, payment_started_at")
})
public class OrderModel extends BaseEntity {

    /** 주문 생성 입력 한 줄. 단가는 Facade 가 카탈로그 BC 에서 받아 넘긴다 (설계 5-1). */
    public record Line(Long productId, Integer quantity, Long unitPrice) {
    }

    /** 참조 개념 사용자 (BC-01). 구매자 = 주문을 만든 사용자 (DR-09). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /** 합계. 생성 시 1회 계산해 저장, 이후 불변 (DR-13). */
    @Column(name = "total_amount", nullable = false, updatable = false)
    private Long totalAmount;

    /** 결제액. CONFIRMED 이면 = totalAmount (INV-09). DRAFT 에는 없다. */
    @Column(name = "paid_amount")
    private Long paidAmount;

    /** 결제 결과: 결제 성공을 반영한 시각 (ASM-13, DR-13). CONFIRMED 에만 있다. */
    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    /** 결제 시도 키. 클라이언트가 만든 confirm 멱등키. AWAITING_PAYMENT 진입 시 1회 기록, 같은 키의 재요청은 현재 상태를 돌려준다. DRAFT 에는 없다. */
    @Column(name = "payment_attempt_key", length = 64)
    private String paymentAttemptKey;

    /** 결제 시작 시각. AWAITING_PAYMENT 진입 시 1회 기록. 결제 대기 만료(정리 job)의 기준이다. DRAFT 에는 없다. */
    @Column(name = "payment_started_at")
    private ZonedDateTime paymentStartedAt;

    /** PG 거래 키. PG 응답·callback 으로 처음 받을 때 1회 기록 (결과 반영 시 비어 있으면 채운다). reconciliation 조회에 쓴다. 받기 전에는 없다. */
    @Column(name = "transaction_key", length = 64)
    private String transactionKey;

    /** 결제수단: 카드 종류. AWAITING_PAYMENT 진입 시 결제 시도 키와 함께 1회 기록. DRAFT 에는 없다. */
    @Column(name = "card_type", length = 20)
    private String cardType;

    /** 결제수단: 마지막 4자리만 남긴 카드번호 ("****-****-****-1451"). 원문은 저장하지 않는다. DRAFT 에는 없다. */
    @Column(name = "masked_card_no", length = 19)
    private String maskedCardNo;

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

    /**
     * ST-03 DRAFT → AWAITING_PAYMENT. 결제 시도 키·결제수단(카드 종류, 마스킹한 카드번호)·결제 시작 시각을 기록한다.
     * 카드 정보는 검증하지 않는다 (PG 가 거절하면 FAILED). 재고 예약은 Facade 가 같은 트랜잭션에서, PG 요청은 커밋 이후 (DR-08 대체).
     */
    public void startPayment(String paymentAttemptKey, String cardType, String cardNo) {
        if (paymentAttemptKey == null || paymentAttemptKey.isBlank() || paymentAttemptKey.length() > 64) {
            throw new CoreException(ErrorType.INVALID_PAYMENT_ATTEMPT_KEY, "결제 시도 키가 올바르지 않습니다. [id = " + getId() + "]");
        }
        ensureDraft();
        this.paymentAttemptKey = paymentAttemptKey;
        this.cardType = cardType;
        this.maskedCardNo = maskCardNo(cardNo);
        this.paymentStartedAt = ZonedDateTime.now();
        this.status = OrderStatus.AWAITING_PAYMENT;
    }

    /** 마지막 4자리만 남긴다. 없으면 null, 4자리 미만이면 전부 가린다. */
    private static String maskCardNo(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            return null;
        }
        if (cardNo.length() < 4) {
            return "****";
        }
        return "****-****-****-" + cardNo.substring(cardNo.length() - 4);
    }

    /** ST-03 AWAITING_PAYMENT → CONFIRMED (결제 성공). 결제액 = 합계 (INV-09), 결제 결과 = 확정 시각 (ASM-13). 결과 반영이 주문 행 락 안에서 호출한다. */
    public void confirm() {
        ensureAwaitingPayment();
        this.paidAmount = this.totalAmount;
        this.confirmedAt = ZonedDateTime.now();
        this.status = OrderStatus.CONFIRMED;
    }

    /** ST-03 AWAITING_PAYMENT → FAILED (결제 실패). 결제액·확정 시각은 비워 둔다. 재고 반환은 결과 반영이 같은 트랜잭션에서. */
    public void fail() {
        ensureAwaitingPayment();
        this.status = OrderStatus.FAILED;
    }

    /** PG 거래 키를 처음 받을 때 1회 기록. 이미 있으면 그대로 둔다 (동기 응답·callback 중 먼저 온 쪽이 기록). */
    public void recordTransactionKey(String transactionKey) {
        if (this.transactionKey == null) {
            this.transactionKey = transactionKey;
        }
    }

    /** 결과 반영(confirm·fail)의 사전 조건. 결과 반영 쪽이 상태를 먼저 확인하므로 여기서 걸리면 코드 버그다. */
    private void ensureAwaitingPayment() {
        if (status != OrderStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException("[id = " + getId() + "] 결제 대기 중인 주문이 아닙니다. [status = " + status + "]");
        }
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 결과 반영 가능 여부. 아니면 이미 결론이 난 주문이라 결과 반영은 아무것도 하지 않는다 (멱등). */
    public boolean isAwaitingPayment() {
        return status == OrderStatus.AWAITING_PAYMENT;
    }

    /** 같은 결제 시도 키로 confirm 을 다시 보낸 것인지. 맞으면 Facade 는 PG 를 다시 부르지 않고 현재 상태를 돌려준다. */
    public boolean isSamePaymentAttempt(String paymentAttemptKey) {
        return this.paymentAttemptKey != null && this.paymentAttemptKey.equals(paymentAttemptKey);
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

    public String getTransactionKey() {
        return transactionKey;
    }

    public ZonedDateTime getPaymentStartedAt() {
        return paymentStartedAt;
    }

    public List<OrderItemModel> getItems() {
        return Collections.unmodifiableList(items);
    }
}
