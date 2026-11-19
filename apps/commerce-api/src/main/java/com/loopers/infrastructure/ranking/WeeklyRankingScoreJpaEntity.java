package com.loopers.infrastructure.ranking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * commerce-batch가 만든 점수와 계산 순위를 읽기 전용으로 매핑한다. 계산 중인 회차는 순위가 비어 있을 수 있다.
 */
@Entity
@Immutable
@IdClass(WeeklyRankingScoreJpaEntity.Key.class)
@Table(
    name = "weekly_ranking_scores",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_ranking_scores_revision_rank", columnNames = {"period_start", "revision", "rank_no"})
)
public class WeeklyRankingScoreJpaEntity {

    @Id
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Id
    @Column(name = "revision", nullable = false)
    private int revision;

    @Id
    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "score", nullable = false)
    private long score;

    @Column(name = "rank_no")
    private Integer rankNo;

    protected WeeklyRankingScoreJpaEntity() {
    }

    public static class Key implements Serializable {

        private LocalDate periodStart;
        private int revision;
        private long productId;

        protected Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && revision == key.revision && productId == key.productId
                && Objects.equals(periodStart, key.periodStart);
        }

        @Override
        public int hashCode() {
            return Objects.hash(periodStart, revision, productId);
        }
    }
}
