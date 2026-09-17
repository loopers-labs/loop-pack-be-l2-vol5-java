package com.loopers.domain.point;

import com.loopers.domain.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 어느 그룹에서 얼마가 충전·사용·만료됐는지 한 줄 (ADR-04). 만든 뒤 바뀌지 않는다.
 * 금액은 부호를 가진다: 충전 +, 사용·만료 −. 한 그룹의 이력 합은 그 그룹의 남은 금액과 같다.
 */
@Entity
@Table(
    name = "point_histories",
    indexes = {
        @Index(name = "idx_point_histories_user", columnList = "user_id"),
        @Index(name = "idx_point_histories_group", columnList = "group_id")
    }
)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @Comment("사용자 식별자")
    private Long userId;

    @Column(name = "group_id", nullable = false)
    @Comment("대상 포인트 그룹 식별자 (ADR-04)")
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    @Comment("이력 종류 — 충전·사용·만료 (ADR-04)")
    private PointHistoryType type;

    @Column(name = "amount", nullable = false)
    @Comment("변동 금액. 충전 +, 사용·만료 − (ADR-04)")
    private long amount;

    @Column(name = "order_id")
    @Comment("사용 이력의 주문 식별자. 충전·만료는 없음 (PNT-05)")
    private Long orderId;

    @Column(name = "occurred_at", nullable = false)
    @Comment("발생 시각")
    private ZonedDateTime occurredAt;

    protected PointHistory() {}

    private PointHistory(Long userId, Long groupId, PointHistoryType type, long amount, Long orderId, ZonedDateTime occurredAt) {
        this.userId = userId;
        this.groupId = groupId;
        this.type = type;
        this.amount = amount;
        this.orderId = orderId;
        this.occurredAt = occurredAt;
    }

    public static PointHistory charge(PointGroup group, ZonedDateTime occurredAt) {
        return new PointHistory(group.getUserId(), group.getId(), PointHistoryType.CHARGE, group.getAmount().amount(), null, occurredAt);
    }

    public static PointHistory use(PointUsage usage, Long orderId, ZonedDateTime occurredAt) {
        PointGroup group = usage.group();
        return new PointHistory(group.getUserId(), group.getId(), PointHistoryType.USE, -usage.amount().amount(), orderId, occurredAt);
    }

    public static PointHistory expire(PointGroup group, Money expired, ZonedDateTime occurredAt) {
        return new PointHistory(group.getUserId(), group.getId(), PointHistoryType.EXPIRE, -expired.amount(), null, occurredAt);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public PointHistoryType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ZonedDateTime getOccurredAt() {
        return occurredAt;
    }
}
