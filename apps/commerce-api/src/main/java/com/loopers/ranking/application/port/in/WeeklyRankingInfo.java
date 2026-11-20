package com.loopers.ranking.application.port.in;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 주간 랭킹 조회 결과 (W9 D-6). 공개본이 없으면 NOT_PUBLISHED + 빈 목록, 공개됐지만 구매가 0건이면 PUBLISHED + 빈 목록.
 */
public record WeeklyRankingInfo(
    Status status,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    ZonedDateTime frozenAt,
    List<Item> items
) {

    public enum Status {
        PUBLISHED,
        NOT_PUBLISHED
    }

    /** rank는 batch가 저장한 순위 그대로다. 삭제 상품을 빼면 1·3위처럼 번호가 빌 수 있다 */
    public record Item(int rank, Long productId, long score, String name, long price, Long brandId, String brandName) {}

    public static WeeklyRankingInfo notPublished(LocalDate periodStart) {
        return new WeeklyRankingInfo(Status.NOT_PUBLISHED, periodStart, periodEnd(periodStart), null, null, List.of());
    }

    public static WeeklyRankingInfo published(LocalDate periodStart, String snapshotId, ZonedDateTime frozenAt, List<Item> items) {
        return new WeeklyRankingInfo(Status.PUBLISHED, periodStart, periodEnd(periodStart), snapshotId, frozenAt, items);
    }

    /** 그 주의 끝 = 다음 월요일 00:00 UTC (그 시각은 포함하지 않는다) */
    private static LocalDate periodEnd(LocalDate periodStart) {
        return periodStart.plusWeeks(1);
    }
}
