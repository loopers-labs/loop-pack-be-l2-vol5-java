package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 매핑·스키마 준비 기준이며 저장은 JDBC 가 맡는다. LocalDateTime 은 UTC 필드다. */
@Entity
@Table(
    name = "ranking_snapshot",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_rs_snapshot", columnNames = {"snapshot_id"}),
        @UniqueConstraint(name = "uk_rs_period_revision", columnNames = {"period_start", "revision"})
    }
)
@Getter
public class RankingSnapshotModel extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false,
        columnDefinition = "varchar(64) character set ascii collate ascii_bin")
    private String snapshotId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16, columnDefinition = "varchar(16)")
    private SnapshotStatus status;

    @Column(name = "input_fixed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime inputFixedAt;

    @Column(name = "input_count", nullable = false)
    private Long inputCount;

    @Column(name = "product_count", nullable = false)
    private Long productCount;

    @Column(name = "input_checksum", nullable = false,
        columnDefinition = "char(64) character set ascii collate ascii_bin")
    private String inputChecksum;

    @Column(name = "result_checksum",
        columnDefinition = "char(64) character set ascii collate ascii_bin")
    private String resultChecksum;

    @Column(name = "completed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime completedAt;

    protected RankingSnapshotModel() {}
}
