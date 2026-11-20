package com.loopers.domain.ranking.score;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/** 계산 중인 상품별 절대 점수. 배치만 읽고, 공개 단계가 완성 ranking 으로 옮긴다. */
@Getter
@Entity
@Table(
    name = "weekly_ranking_score_stage",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_ranking_score_stage", columnNames = {"snapshot_id", "product_id"}
    )
)
public class WeeklyRankingScoreStage extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "score", nullable = false)
    private long score;

    // JPA 가 사용하는 기본 생성자.
    protected WeeklyRankingScoreStage() {}

    public WeeklyRankingScoreStage(String snapshotId, ProductScore score) {
        this.snapshotId = snapshotId;
        this.productId = score.productId();
        this.score = score.score();
    }

    /** 같은 상품을 다시 처리해도 더하지 않고 같은 값으로 덮는다. */
    public void overwrite(ProductScore score) {
        if (!productId.equals(score.productId())) {
            throw new IllegalArgumentException("다른 상품의 점수입니다: " + score.productId());
        }
        this.score = score.score();
    }

    public ProductScore toScore() {
        return new ProductScore(productId, score);
    }
}
