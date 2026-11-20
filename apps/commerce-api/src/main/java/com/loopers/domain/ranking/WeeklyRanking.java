package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * commerce-batch 가 공개한 완성 ranking. 계산 당시 순위를 그대로 보존한다.
 * 컬럼 정의는 batch 의 WeeklyRanking 과 같게 유지한다.
 */
@Getter
@Immutable
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
}
