package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 소비 완료한 OrderConfirmed 이벤트의 장부 (설계 D10). 이벤트당 1행이며 eventId·orderId 가 각각 유일하다.
 * fingerprint 는 정규화한 payload 문자열로, 같은 eventId 로 다른 사실이 오면 이 값으로 충돌을 드러낸다.
 */
@Getter
@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_poc_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_poc_order_id", columnNames = "order_id")
    }
)
public class ProcessedOrderConfirmation extends BaseEntity {

    private String eventId;

    private Long orderId;

    private ZonedDateTime confirmedAt;

    private String fingerprint;

    private ZonedDateTime processedAt;

    protected ProcessedOrderConfirmation() {}

    /**
     * processedAt 은 호출자가 Clock 으로 찍어 넘긴다. 엔티티가 now() 를 직접 부르지 않아 테스트에서 고정할 수 있다.
     */
    public ProcessedOrderConfirmation(
        String eventId,
        Long orderId,
        ZonedDateTime confirmedAt,
        String fingerprint,
        ZonedDateTime processedAt
    ) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("장부의 eventId 는 필수입니다.");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("장부의 orderId 는 필수입니다.");
        }
        if (confirmedAt == null) {
            throw new IllegalArgumentException("장부의 confirmedAt 은 필수입니다.");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("장부의 fingerprint 는 필수입니다.");
        }
        if (processedAt == null) {
            throw new IllegalArgumentException("장부의 processedAt 은 필수입니다.");
        }
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt;
        this.fingerprint = fingerprint;
        this.processedAt = processedAt;
    }

    /**
     * 장부의 사실과 새 이벤트를 비교한다 (설계 D10 표). 같은 사실이면 아무 일도 없고(no-op),
     * 같은 orderId 의 다른 eventId 또는 같은 eventId 의 다른 fingerprint 면 충돌(영구 오류)이다.
     */
    public void verifySameFact(OrderConfirmedEvent event) {
        String newFingerprint = event.fingerprint();
        if (!this.eventId.equals(event.eventId())) {
            throw new PermanentEventException(String.format(
                "같은 orderId 에 다른 eventId 가 왔습니다. orderId=%d, existingEventId=%s, newEventId=%s",
                orderId, eventId, event.eventId()), fingerprint, newFingerprint);
        }
        if (!this.fingerprint.equals(newFingerprint)) {
            throw new PermanentEventException(String.format(
                "같은 eventId 에 다른 사실이 왔습니다. eventId=%s, orderId=%d, existingFingerprint=%s, newFingerprint=%s",
                eventId, orderId, fingerprint, newFingerprint), fingerprint, newFingerprint);
        }
    }
}
