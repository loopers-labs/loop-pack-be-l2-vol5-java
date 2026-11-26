package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 주간 랭킹 집계의 입력 행. snapshotId 로 집계 대상 묶음을 구분하고, 그 안에서 orderId·productId 당 1행이다.
 */
@Getter
@Entity
@Table(
    name = "weekly_ranking_inputs",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_wri_snapshot_order_product",
        columnNames = {"snapshot_id", "order_id", "product_id"}
    )
)
public class WeeklyRankingInput extends BaseEntity {

    @Column(length = 64, nullable = false)
    private String snapshotId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long productId;

    private int quantity;

    @Column(nullable = false)
    private ZonedDateTime confirmedAt;

    protected WeeklyRankingInput() {}

    public WeeklyRankingInput(String snapshotId, Long orderId, Long productId, int quantity, ZonedDateTime confirmedAt) {
        this.snapshotId = snapshotId;
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
    }
}
