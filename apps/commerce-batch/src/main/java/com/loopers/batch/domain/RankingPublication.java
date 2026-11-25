package com.loopers.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 공개 pointer — 주마다 한 행, API가 보여 줄 snapshot을 가리킨다 (W9 D-2·D-5). 공개 step이 revision을 비교해 바꾼다.
 * 테이블 정의용 매핑이다. 쓰기와 읽기는 JDBC로 한다.
 */
@Entity
@Table(name = "ranking_publication")
public class RankingPublication {

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

    protected RankingPublication() {}
}
