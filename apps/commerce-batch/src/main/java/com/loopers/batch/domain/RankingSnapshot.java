package com.loopers.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * snapshot 정보 — 한 주·한 revision의 계산 한 벌 (W9 D-2). 입력 고정 시점과 고정 입력의 요약(건수·수량 합·checksum)을 갖는다.
 * 테이블 정의용 매핑이다. 쓰기와 읽기는 JDBC로 한다.
 */
@Entity
@Table(
    name = "ranking_snapshot",
    uniqueConstraints = @UniqueConstraint(name = "uk_ranking_snapshot_period_revision", columnNames = {"period_start", "revision"})
)
public class RankingSnapshot {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 32)
    private String snapshotId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SnapshotStatus status;

    @Column(name = "frozen_at", nullable = false)
    private ZonedDateTime frozenAt;

    @Column(name = "input_count", nullable = false)
    private long inputCount;

    @Column(name = "input_quantity_sum", nullable = false)
    private long inputQuantitySum;

    @Column(name = "input_checksum", nullable = false, length = 64)
    private String inputChecksum;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected RankingSnapshot() {}
}
