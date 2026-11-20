package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/** 기간별 revision 카운터와 공개 pointer. 같은 기간의 고정·공개는 이 행을 잠가 직렬로 실행한다. */
@Getter
@Entity
@Table(
    name = "weekly_ranking_publication",
    uniqueConstraints = @UniqueConstraint(name = "uk_weekly_ranking_publication_period_start", columnNames = "period_start")
)
public class WeeklyRankingPublication extends BaseEntity {

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "last_revision", nullable = false)
    private int lastRevision;

    @Column(name = "published_snapshot_id", length = 64)
    private String publishedSnapshotId;

    @Column(name = "published_revision")
    private Integer publishedRevision;

    // JPA 가 사용하는 기본 생성자.
    protected WeeklyRankingPublication() {}

    public WeeklyRankingPublication(LocalDate periodStart) {
        this.periodStart = new WeeklyPeriod(periodStart).periodStart();
        this.lastRevision = 0;
    }

    public void assignRevision(int revision) {
        if (revision != lastRevision + 1) {
            throw new RevisionConflictException(
                periodStart + " 의 새 revision 은 " + (lastRevision + 1) + " 이어야 합니다: " + revision);
        }
        this.lastRevision = revision;
    }

    /** 더 새 revision 이 이미 공개돼 있으면 pointer 를 되돌리지 않는다. */
    public PublishOutcome publish(WeeklyRankingSnapshot snapshot, String resultChecksum, ZonedDateTime publishedAt) {
        if (!periodStart.equals(snapshot.getPeriodStart())) {
            throw new IllegalArgumentException("다른 기간의 snapshot 입니다: " + snapshot.getSnapshotId());
        }
        if (publishedRevision != null && publishedRevision > snapshot.getRevision()) {
            snapshot.supersede();
            return PublishOutcome.SUPERSEDED;
        }
        snapshot.publish(resultChecksum, publishedAt);
        this.publishedSnapshotId = snapshot.getSnapshotId();
        this.publishedRevision = snapshot.getRevision();
        return PublishOutcome.PUBLISHED;
    }
}
