package com.loopers.domain.ranking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 주간 랭킹 회차(snapshot 정보). snapshotId 가 곧 회차라서 문자열 업무 식별자를 PK 로 두고 BaseEntity 를 상속하지 않는다 (설계 D3).
 * 행이 존재한다 ⇔ Step 1 이 입력 복사와 같은 TX 로 commit 했다. 쓰기는 Step 이 JDBC 로 하며, 이 클래스는 테이블 정의의 원본이다.
 */
@Getter
@Entity
@Table(
    name = "weekly_ranking_revisions",
    uniqueConstraints = @UniqueConstraint(name = "uk_wrr_period_revision", columnNames = {"period_start", "revision"})
)
public class WeeklyRankingRevision {

    /** 실행 실패는 Batch metadata 가 기록하므로 FAILED 는 두지 않는다 (설계 D3). */
    public enum Status { CALCULATING, COMPLETED }

    @Id
    @Column(length = 64)
    private String snapshotId;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private int revision;

    /** Hibernate 6 는 MySQL 에서 enum 을 네이티브 ENUM 타입으로 만들므로 설계 DDL(VARCHAR(20))에 맞춰 타입을 고정한다. */
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", nullable = false)
    private Status status;

    @Column(nullable = false)
    private ZonedDateTime frozenAt;

    @Column(nullable = false)
    private int inputCount;

    @Column(nullable = false)
    private int expectedProductCount;

    @Column(columnDefinition = "CHAR(64)", nullable = false)
    private String inputChecksum;

    @Column(columnDefinition = "CHAR(64)")
    private String resultChecksum;

    @Column(nullable = false)
    private ZonedDateTime createdAt;

    @Column(nullable = false)
    private ZonedDateTime updatedAt;

    protected WeeklyRankingRevision() {}
}
