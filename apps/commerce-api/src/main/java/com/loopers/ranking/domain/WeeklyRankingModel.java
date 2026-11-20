package com.loopers.ranking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 점수·순위 (W9 D-2). commerce-batch가 쓰고, 여기서는 공개 pointer가 가리키는 snapshot의 행만 읽는다.
 */
@Getter
@Entity
@Table(
    name = "weekly_ranking",
    uniqueConstraints = @UniqueConstraint(name = "uk_weekly_ranking_snapshot_product", columnNames = {"snapshot_id", "product_id"}),
    indexes = @Index(name = "idx_weekly_ranking_snapshot_rank", columnList = "snapshot_id, rank_no")
)
public class WeeklyRankingModel {

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

    protected WeeklyRankingModel() {}
}
