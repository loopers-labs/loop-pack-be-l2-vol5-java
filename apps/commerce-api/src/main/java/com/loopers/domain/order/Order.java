package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// "order"는 SQL 예약어라 테이블명은 "orders"로 둔다(Like를 "likes"로 둔 것과 같은 이유).
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private Long userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    // items는 Order 자신의 VO 모음(별도 aggregate가 아님)이라 항상 함께 필요하다 — LAZY로 두면
    // 트랜잭션 밖(Facade의 OrderInfo.from())에서 LazyInitializationException이 난다.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_item", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    protected Order() {}

    /**
     * DRAFT 생성. 품목이 하나도 없으면 거절하고, 같은 productId가 여러 줄로 들어오면 수량을 합쳐 하나로 만든다
     * (docs/week2/design.md 3번 섹션·5번 섹션 불변식 표 참고). 단가는 호출자가 이미 조회해 넘긴 값을
     * 그대로 스냅샷으로 저장한다 — Order는 Product를 직접 조회하지 않는다.
     */
    public Order(Long userId, List<OrderItemDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "품목이 하나도 없는 주문은 생성할 수 없습니다.");
        }
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = merge(drafts);
    }

    private static List<OrderItem> merge(List<OrderItemDraft> drafts) {
        Map<Long, Integer> quantityByProductId = new LinkedHashMap<>();
        Map<Long, Long> unitPriceByProductId = new LinkedHashMap<>();
        for (OrderItemDraft draft : drafts) {
            if (draft.quantity() <= 0) {
                throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 양수여야 합니다.");
            }
            quantityByProductId.merge(draft.productId(), draft.quantity(), Integer::sum);
            unitPriceByProductId.putIfAbsent(draft.productId(), draft.unitPrice());
        }
        List<OrderItem> merged = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantityByProductId.entrySet()) {
            merged.add(new OrderItem(entry.getKey(), entry.getValue(), unitPriceByProductId.get(entry.getKey())));
        }
        return merged;
    }

    /**
     * DRAFT가 아니면(이미 확정·결제 진행·결제 실패) 409로 거절한다. 읽기 전용 사전 확인 — Product/User를 건드리기 전에
     * Facade가 먼저 호출해 실패를 앞당긴다(docs/week2/design.md 4번 섹션 시퀀스 다이어그램 1단계).
     */
    public void assertDraft() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "결제를 시작할 수 없는 상태의 주문입니다. (현재: " + status + ")");
        }
    }

    public long calculateTotalAmount() {
        return items.stream().mapToLong(item -> item.getUnitPrice() * item.getQuantity()).sum();
    }

    /**
     * 포인트 결제로 바로 CONFIRMED로 전이하고 결제액·확정시각을 저장한다(4주차까지의 확정 경로).
     * assertDraft()를 다시 실행해 재확정을 막는다 — Facade의 사전 확인과 별개로, 이 메서드 자체도 스스로 불변식을 지킨다.
     */
    public void confirm(long paidAmount) {
        assertDraft();
        this.status = OrderStatus.CONFIRMED;
        this.paymentMethod = PaymentMethod.POINT;
        this.paidAmount = paidAmount;
        this.confirmedAt = confirmedNow();
    }

    /**
     * PG 결제를 시작한다(TX A). 재고 예약과 결제 시도 생성은 같은 트랜잭션에서 호출자가 한다.
     */
    public void startPgPayment() {
        assertDraft();
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentMethod = PaymentMethod.PG;
    }

    /**
     * PG 결제가 SUCCESS로 확인됐다(TX B). 결제 시도의 전이가 일어났을 때만 호출된다(INV-002).
     */
    public void completePayment(long paidAmount) {
        assertPaymentPending();
        this.status = OrderStatus.CONFIRMED;
        this.paidAmount = paidAmount;
        this.confirmedAt = confirmedNow();
    }

    /**
     * PG 결제가 FAILED로 확인됐다(TX B). 예약 반환은 호출자가 같은 트랜잭션에서 한다(INV-001).
     */
    public void failPayment() {
        assertPaymentPending();
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    /**
     * 확정 시각은 정해지는 이 자리에서 DB가 담을 모양(UTC, 마이크로초 — 컬럼 datetime(6), NORMALIZE_UTC)으로 맞춘다
     * (docs/week6/decisions.md D-6). 그래야 응답·이벤트·DB 재조회·재발행 어디로 흘러가도 글자 그대로 같다(INV-602).
     * 이벤트가 나가지 않는 confirm()도 같은 이유로 맞춘다 — 근거가 "이벤트 경로"가 아니라 "확정 시각이 정해지는 자리"다.
     */
    private static ZonedDateTime confirmedNow() {
        return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private void assertPaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new CoreException(ErrorType.CONFLICT, "결제 진행 중인 주문이 아닙니다. (현재: " + status + ")");
        }
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    /**
     * 아직 가격이 매겨진(호출자가 Product 조회를 마친) 주문 품목 한 줄 — 병합 전 입력.
     */
    public record OrderItemDraft(Long productId, int quantity, Long unitPrice) {}
}
