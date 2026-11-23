package com.loopers.batch.job.ranking.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 회차의 상품별 점수와 계산 순위. 점수는 점수 고정 트랜잭션에서 순위 없이 한 번 넣고 바꾸지 않는다.
 * 순위는 순위 Step이 채우고, 과거 회차의 행은 지우지 않는다.
 * 같은 회차의 순위 유일 제약과 점수 양수 제약은 테스트 스키마에도 생기도록 매핑에 선언한다.
 * 스키마의 원본은 docker/mysql의 SQL 파일이다. 이 매핑은 local의 스키마 확인(validate)과 test 스키마 생성에만 쓰고,
 * 읽기·쓰기는 JdbcTemplate SQL로 한다.
 */
@Entity
@IdClass(WeeklyRankingScoreJpaEntity.Key.class)
@Table(
    name = "weekly_ranking_scores",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_ranking_scores_revision_rank", columnNames = {"period_start", "revision", "rank_no"})
)
@Check(name = "ck_weekly_ranking_scores_score", constraints = "score > 0")
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
