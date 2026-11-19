package com.loopers.infrastructure.ranking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.FractionalSeconds;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * commerce-batch가 쓰는 회차 정보를 읽기 전용으로 매핑한다. 완성된 회차의 번호와 계산 시각을 읽는다.
 * 계산 중인 회차는 결과 checksum이 비어 있다.
 */
@Entity
@Immutable
@IdClass(WeeklyRankingRevisionJpaEntity.Key.class)
@Table(name = "weekly_ranking_revisions")
public class WeeklyRankingRevisionJpaEntity {

    @Id
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Id
    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

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
