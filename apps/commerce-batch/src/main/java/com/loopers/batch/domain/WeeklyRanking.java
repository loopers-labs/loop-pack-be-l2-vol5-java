package com.loopers.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 점수·순위 (W9 D-2). 점수 계산 step이 score를 upsert하고, 공개 step이 rank_no를 채운다. rank_no가 NULL이면 아직 순위가 없는 계산 중 행이다.
 * 테이블 정의용 매핑이다. batch는 JDBC로 쓰고, commerce-api는 별도의 읽기 전용 엔티티로 읽는다.
 */
@Entity
@Table(
    name = "weekly_ranking",
    uniqueConstraints = @UniqueConstraint(name = "uk_weekly_ranking_snapshot_product", columnNames = {"snapshot_id", "product_id"}),
    indexes = @Index(name = "idx_weekly_ranking_snapshot_rank", columnList = "snapshot_id, rank_no")
)
public class WeeklyRanking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false, length = 32)
    private String snapshotId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "score", nullable = false)
    private long score;

    @Column(name = "rank_no")
    private Integer rankNo;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected WeeklyRanking() {}
}
