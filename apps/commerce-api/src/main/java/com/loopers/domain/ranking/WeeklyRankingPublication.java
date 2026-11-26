package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/**
 * commerce-batch 가 쓰는 기간별 공개 pointer. api 는 읽기만 한다.
 * 컬럼 정의는 batch 의 WeeklyRankingPublication 과 같게 유지한다.
 */
@Getter
@Immutable
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
}
