package com.loopers.batch.job.ranking.schema;

import com.loopers.batch.job.ranking.WeeklyRankingRevisionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.FractionalSeconds;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 회차 정보. 점수 고정 트랜잭션에서 CALCULATING으로 만들고, 공개 트랜잭션에서 결과 checksum을 채워 COMPLETED로 바꾼다.
 * 닫을 때는 상태와 닫은 시각·사유만 바꾼다.
 * 기본키가 (period_start, revision)이라 BaseEntity를 상속하지 않는다.
 * 스키마의 원본은 docker/mysql의 SQL 파일이다. 이 매핑은 local의 스키마 확인(validate)과 test 스키마 생성에만 쓰고,
 * 읽기·쓰기는 JdbcTemplate SQL로 한다.
 */
@Entity
@IdClass(WeeklyRankingRevisionJpaEntity.Key.class)
@Table(name = "weekly_ranking_revisions")
public class WeeklyRankingRevisionJpaEntity {

    @Id
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Id
    @Column(name = "revision", nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WeeklyRankingRevisionStatus status;

    @Column(name = "calculated_at", nullable = false)
    @FractionalSeconds(6)
    private Instant calculatedAt;

    @Column(name = "result_checksum", length = 64)
    private String resultChecksum;

    @Column(name = "job_execution_id", nullable = false)
    private long jobExecutionId;

    @Column(name = "cancelled_at")
    @FractionalSeconds(6)
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected WeeklyRankingRevisionJpaEntity() {
    }

    public static class Key implements Serializable {

        private LocalDate periodStart;
        private int revision;

        protected Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && revision == key.revision && Objects.equals(periodStart, key.periodStart);
        }

        @Override
        public int hashCode() {
            return Objects.hash(periodStart, revision);
        }
    }
}
