package com.loopers.domain.product.entity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * snapshot 의 상품별 주간 점수와 계산 순위. 점수는 계산 step 이 절대값으로 쓰고, 순위는 공개 step 이 채운다 (9주차 D-181)
 */
@Getter
@Entity
@Table(
    name = "weekly_ranking_score",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_ranking_score_snapshot_product",
        columnNames = {"snapshot_id", "product_id"}
    ),
    indexes = @Index(name = "idx_weekly_ranking_score_snapshot_ranking", columnList = "snapshot_id, ranking")
)
public class WeeklyRankingScore extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private long score;

    /** 공개 전에는 비어 있다. RANK 는 MySQL 예약어라 ranking 으로 씀 */
    @Column(name = "ranking")
    private Integer ranking;

    protected WeeklyRankingScore() {}
}
