package com.loopers.application.ranking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 주간 랭킹 조회 결과. 공개본이 없으면 NOT_PUBLISHED(snapshotId 없음, 빈 items),
 * 구매가 없던 완성본이면 PUBLISHED(snapshotId 있음, 빈 items)로 구분된다.
 */
public record WeeklyRankingInfo(
    Status status,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    Integer revision,
    Instant frozenAt,
    List<Item> items
) {
    public enum Status {
        NOT_PUBLISHED,
        PUBLISHED
    }

    public record Item(int rank, long productId, long score, String name, String brandName, Long price) {
    }

    public static WeeklyRankingInfo notPublished(LocalDate periodStart) {
        return new WeeklyRankingInfo(Status.NOT_PUBLISHED, periodStart, periodStart.plusDays(7), null, null, null, List.of());
    }
}
