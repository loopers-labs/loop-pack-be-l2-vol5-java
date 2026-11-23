package com.loopers.domain.ranking;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 랭킹 조회 SQL 의 불변 projection. 시각은 UTC 필드다. */
public final class WeeklyRankingQueryResult {

    private WeeklyRankingQueryResult() {}

    /** 기간 pointer 와 연결 snapshot. 연결 snapshot 이 없으면 snapshot 은 null 이다. */
    public record Publication(LocalDate periodStart, String publishedSnapshotId, boolean deleted, SnapshotSummary snapshot) {}

    public record SnapshotSummary(
        String snapshotId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        LocalDateTime inputFixedAt,
        LocalDateTime completedAt,
        String resultChecksum,
        boolean deleted
    ) {}

    public record Item(Long productId, String productName, BigInteger score, Long rank) {}
}
