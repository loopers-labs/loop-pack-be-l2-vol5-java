package com.loopers.ranking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 공개 pointer — 주마다 한 행, 그 주에 보여 줄 snapshot (W9 D-2·D-5). commerce-batch의 공개 step이 쓰고, 여기서는 읽기만 한다.
 */
@Getter
@Entity
@Table(name = "ranking_publication")
public class RankingPublicationModel {

    @Id
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "snapshot_id", nullable = false, length = 32)
    private String snapshotId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "published_at", nullable = false)
    private ZonedDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected RankingPublicationModel() {}
}
