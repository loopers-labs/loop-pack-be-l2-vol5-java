package com.loopers.domain.ranking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 기간별 공개 pointer. "기간당 공개본은 하나" 를 period_start PK 로 표현하므로 BaseEntity 를 상속하지 않는다 (설계 D5).
 * 갱신은 Step 3 이 revision <= 조건부 UPDATE 로 하며, 이 클래스는 테이블 정의의 원본이다.
 */
@Getter
@Entity
@Table(name = "weekly_ranking_publications")
public class WeeklyRankingPublication {

    @Id
    private LocalDate periodStart;

    @Column(length = 64, nullable = false)
    private String snapshotId;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private ZonedDateTime publishedAt;

    @Column(nullable = false)
    private ZonedDateTime updatedAt;

    protected WeeklyRankingPublication() {}
}
