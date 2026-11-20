package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * snapshot 안 상품별 점수 행. 점수 stage 와 완성 ranking 을 한 테이블로 합쳤다 (설계 D4, 불변 규칙 R1~R7).
 * score 는 Step 2 가 절대값으로 upsert 하고(R1·R2), ranking 은 Step 3 이 모든 행에 한 TX 로 채운다(R3). 컬럼명 rank 는 MySQL 8 예약어라 ranking.
 */
@Getter
@Entity
@Table(
    name = "weekly_ranking_scores",
    uniqueConstraints = @UniqueConstraint(name = "uk_wrs_snapshot_product", columnNames = {"snapshot_id", "product_id"}),
    indexes = @Index(name = "idx_wrs_snapshot_ranking", columnList = "snapshot_id, ranking")
)
public class WeeklyRankingScore extends BaseEntity {

    @Column(length = 64, nullable = false)
    private String snapshotId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private long score;

    private Integer ranking;

    protected WeeklyRankingScore() {}
}
