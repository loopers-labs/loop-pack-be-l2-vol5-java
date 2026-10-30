package com.loopers.domain.purchaseactivity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Check;

import java.time.ZonedDateTime;

/**
 * 구매 활동으로 반영을 끝낸 주문 확정 사건(소비 설계 3절). 같은 TX 의 모든 활동과 함께 commit 되며 처리 중 상태는 없다.
 * 두 unique 가 같은 사건·주문의 중복 반영을 DB 에서 막는다. 주문 테이블과 물리 FK 는 두지 않는다. 생성 뒤 수정 경로가 없다.
 */
@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_poc_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_poc_order", columnNames = "order_id")
    }
)
@Check(name = "ck_poc_order_id", constraints = "order_id > 0")
@Check(name = "ck_poc_item_count", constraints = "item_count > 0")
@Getter
public class ProcessedOrderConfirmationModel extends BaseEntity {

    // 사건 ID·유형은 대소문자를 구분하는 ASCII binary collation 이다. 기본 생성으로는 나오지 않아 열 정의를 적는다.
    @Column(name = "event_id", nullable = false, updatable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false,
        columnDefinition = "varchar(32) character set ascii collate ascii_bin")
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private Integer schemaVersion;

    /** 사건의 최초 확정 순간. 소비 시각이 아니다. */
    @Column(name = "confirmed_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private ZonedDateTime confirmedAt;

    /** 정규화한 상품 종류 수. 활동 누락·손상 검사에 쓴다. */
    @Column(name = "item_count", nullable = false, updatable = false)
    private Integer itemCount;

    protected ProcessedOrderConfirmationModel() {}

    private ProcessedOrderConfirmationModel(OrderConfirmation event) {
        this.eventId = event.eventId();
        this.orderId = event.orderId();
        this.eventType = event.eventType();
        this.schemaVersion = event.schemaVersion();
        this.confirmedAt = event.confirmedAt();
        this.itemCount = event.items().size();
    }

    public static ProcessedOrderConfirmationModel completed(OrderConfirmation event) {
        return new ProcessedOrderConfirmationModel(event);
    }
}
