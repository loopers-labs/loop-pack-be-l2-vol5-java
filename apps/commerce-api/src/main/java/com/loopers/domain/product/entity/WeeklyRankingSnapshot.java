package com.loopers.domain.product.entity;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.product.enums.RankingSnapshotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 주간 랭킹 계산 한 묶음의 manifest. 쓰기는 commerce-batch 가 JDBC 로 하고, 스키마는 이 엔티티가 만든다 (9주차 D-182, D-183)
 */
@Getter
@Entity
@Table(name = "weekly_ranking_snapshot", uniqueConstraints = {
    @UniqueConstraint(name = "uk_weekly_ranking_snapshot_snapshot_id", columnNames = "snapshot_id"),
    @UniqueConstraint(name = "uk_weekly_ranking_snapshot_period_revision", columnNames = {"period_start", "revision"}),
    // 기간마다 공개본은 하나 (D-180)
    @UniqueConstraint(name = "uk_weekly_ranking_snapshot_published_period", columnNames = "published_period")
})
public class WeeklyRankingSnapshot extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    /** UTC 월요일. 기간은 [periodStart, periodStart + 7일) */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RankingSnapshotStatus status;

    /** 표시 · 기록용. 어떤 활동이 들어갔는지의 기준은 입력 행이다 (D-179) */
    @Column(name = "frozen_at", nullable = false)
    private ZonedDateTime frozenAt;

    @Column(name = "input_count", nullable = false)
    private int inputCount;

    @Column(name = "input_checksum", nullable = false, length = 64)
    private String inputChecksum;

    /** MySQL 에 조건부 유일 인덱스가 없어, PUBLISHED 일 때만 값이 있는 생성 컬럼에 유일 인덱스를 건다 (D-180) */
    @Column(
        name = "published_period",
        insertable = false,
        updatable = false,
        columnDefinition = "DATE AS (IF(status = 'PUBLISHED', period_start, NULL)) VIRTUAL"
    )
    private LocalDate publishedPeriod;

    protected WeeklyRankingSnapshot() {}
}
