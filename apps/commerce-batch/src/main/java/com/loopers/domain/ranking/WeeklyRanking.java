package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/** 공개 단계가 만든 완성 ranking. 계산 당시 순위를 보존한다. */
@Getter
@Entity
@Table(
    name = "weekly_ranking",
    uniqueConstraints = @UniqueConstraint(name = "uk_weekly_ranking", columnNames = {"snapshot_id", "product_id"}),
    indexes = @Index(name = "idx_weekly_ranking_snapshot_rank", columnList = "snapshot_id, ranking")
)
public class WeeklyRanking extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "score", nullable = false)
    private long score;

    // rank 는 MySQL 8 예약어라 컬럼 이름을 ranking 으로 둔다.
    @Column(name = "ranking", nullable = false)
    private int ranking;

    // JPA 가 사용하는 기본 생성자.
    protected WeeklyRanking() {}

    public WeeklyRanking(String snapshotId, RankedProduct product) {
        this.snapshotId = snapshotId;
        this.productId = product.productId();
        this.score = product.score();
        this.ranking = product.rank();
    }
}
