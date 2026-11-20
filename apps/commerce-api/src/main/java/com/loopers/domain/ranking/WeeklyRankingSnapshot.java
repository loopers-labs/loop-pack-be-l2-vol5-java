package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * commerce-batch 가 고정한 입력 묶음의 manifest. api 는 공개된 snapshot 의 기간·revision·입력 고정 시점만 읽는다.
 * 컬럼 정의는 batch 의 WeeklyRankingSnapshot 과 같게 유지한다.
 */
@Getter
@Immutable
@Entity
@Table(
    name = "weekly_ranking_snapshot",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_weekly_ranking_snapshot_id", columnNames = "snapshot_id"),
        @UniqueConstraint(name = "uk_weekly_ranking_snapshot_period_revision", columnNames = {"period_start", "revision"})
    }
)
public class WeeklyRankingSnapshot extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "frozen_at", nullable = false)
    private ZonedDateTime frozenAt;

    @Column(name = "input_count", nullable = false)
    private int inputCount;

    @Column(name = "product_count", nullable = false)
    private int productCount;

    @Column(name = "input_checksum", nullable = false, length = 64)
    private String inputChecksum;

    @Column(name = "result_checksum", length = 64)
    private String resultChecksum;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    // JPA 가 사용하는 기본 생성자.
    protected WeeklyRankingSnapshot() {}
}
