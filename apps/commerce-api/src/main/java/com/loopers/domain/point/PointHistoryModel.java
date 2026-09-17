package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 포인트 변경의 원인과 결과를 남기는 기록. 현재 잔액의 기준은 PointModel 이다.
 */
@Entity
@Table(name = "point_history")
@Getter
public class PointHistoryModel extends BaseEntity {

    @Column(name = "point_id", nullable = false)
    private Long pointId;

    @Column(name = "before_balance", nullable = false)
    private long beforeBalance;

    @Column(name = "after_balance", nullable = false)
    private long afterBalance;

    @Column(name = "changed_amount", nullable = false)
    private long changedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointChangeCause cause;

    @Column(name = "order_id")
    private Long orderId;

    protected PointHistoryModel() {}

    private PointHistoryModel(Long pointId, PointChange change, PointChangeCause cause, Long orderId) {
        this.pointId = pointId;
        this.beforeBalance = change.beforeBalance();
        this.afterBalance = change.afterBalance();
        this.changedAmount = change.changedAmount();
        this.cause = cause;
        this.orderId = orderId;
    }

    public static PointHistoryModel charged(Long pointId, PointChange change) {
        return new PointHistoryModel(pointId, change, PointChangeCause.CHARGE, null);
    }

    public static PointHistoryModel usedForOrder(Long pointId, Long orderId, PointChange change) {
        return new PointHistoryModel(pointId, change, PointChangeCause.ORDER_USE, orderId);
    }
}
