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
import java.time.ZonedDateTime;

/** 고정 입력 묶음의 manifest. 건수·checksum 으로 입력이 바뀌지 않았음을 확인한다. */
@Getter
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SnapshotStatus status;

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

    // 어느 실행이 입력을 고정했는지 남기는 소속 증거. 결과 비교(checksum)에는 넣지 않는다.
    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    // JPA 가 사용하는 기본 생성자.
    protected WeeklyRankingSnapshot() {}

    public static WeeklyRankingSnapshot freeze(WeeklyPeriod period, int revision, ZonedDateTime frozenAt,
                                               int inputCount, int productCount, String inputChecksum,
                                               Long jobExecutionId) {
        WeeklyRankingSnapshot snapshot = new WeeklyRankingSnapshot();
        snapshot.snapshotId = period.snapshotId(revision);
        snapshot.periodStart = period.periodStart();
        snapshot.revision = revision;
        snapshot.status = SnapshotStatus.FROZEN;
        snapshot.frozenAt = frozenAt;
        snapshot.inputCount = inputCount;
        snapshot.productCount = productCount;
        snapshot.inputChecksum = inputChecksum;
        snapshot.jobExecutionId = jobExecutionId;
        return snapshot;
    }

    public void verifyInput(int actualCount, String actualChecksum) {
        if (inputCount != actualCount || !inputChecksum.equals(actualChecksum)) {
            throw new IllegalStateException(snapshotId + " 의 고정 입력이 manifest 와 다릅니다. 건수 "
                + inputCount + " → " + actualCount);
        }
    }

    public void verifyScoredProducts(long scoredCount) {
        if (productCount != scoredCount) {
            throw new IllegalStateException(snapshotId + " 의 계산된 상품 수가 고정 입력과 다릅니다. 기대 "
                + productCount + ", 실제 " + scoredCount);
        }
    }

    void publish(String resultChecksum, ZonedDateTime publishedAt) {
        requireFrozen();
        this.status = SnapshotStatus.PUBLISHED;
        this.resultChecksum = resultChecksum;
        this.publishedAt = publishedAt;
    }

    void supersede() {
        requireFrozen();
        this.status = SnapshotStatus.SUPERSEDED;
    }

    private void requireFrozen() {
        if (status != SnapshotStatus.FROZEN) {
            throw new IllegalStateException(snapshotId + " 는 이미 " + status + " 입니다.");
        }
    }
}
