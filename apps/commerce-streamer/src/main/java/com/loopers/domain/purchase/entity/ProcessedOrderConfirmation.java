package com.loopers.domain.purchase.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 주문 확정 이벤트를 처리했다는 기록. 구매 활동과 같은 TX 에서 저장해 "기록만 있고 활동은 없는" 상태를 막음 (6주차 설계 4.4).
 * eventId 는 메시지의 신원, orderId 는 "확정은 한 번"이라는 업무 키라 따로 유일하게 지킴 (D-76)
 */
@Getter
@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = "uk_processed_order_confirmation_event_id", columnNames = "event_id"),
    @UniqueConstraint(name = "uk_processed_order_confirmation_order_id", columnNames = "order_id")
})
public class ProcessedOrderConfirmation extends BaseEntity {

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 구매 활동은 orderId 로 join 해 확정 시각을 얻음 (D-78) */
    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    protected ProcessedOrderConfirmation() {}

    public ProcessedOrderConfirmation(OrderConfirmation confirmation) {
        this.eventId = confirmation.eventId();
        this.orderId = confirmation.orderId();
        this.confirmedAt = confirmation.confirmedAt();
    }

    /** 다시 읽은 값은 시간대 표현이 다를 수 있어 시점으로 비교함 */
    public boolean hasSameConfirmedAt(OrderConfirmation confirmation) {
        return confirmedAt.toInstant().equals(confirmation.confirmedAt().toInstant());
    }
}
