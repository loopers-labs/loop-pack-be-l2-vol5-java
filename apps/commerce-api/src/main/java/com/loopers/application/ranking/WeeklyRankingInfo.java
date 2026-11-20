package com.loopers.application.ranking;

import com.loopers.application.product.CustomerProductInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 요청한 주의 공개 랭킹. 공개본이 없으면 {@link Status#NOT_PUBLISHED}이고 snapshot 정보와 items가 비어 있다.
 * 구매가 없던 주의 완성본은 {@link Status#PUBLISHED}이면서 items가 비어 있다.
 */
public record WeeklyRankingInfo(
    LocalDate periodStart,
    LocalDate periodEnd,
    Status status,
    String snapshotId,
    Integer revision,
    Instant inputFrozenAt,
    List<Item> items
) {
    public enum Status { PUBLISHED, NOT_PUBLISHED }

    /** rank는 배치가 계산한 순위다. 비노출 상품을 뺀 뒤 다시 매기지 않으므로 빈칸이 있을 수 있다. */
    public record Item(int rank, long score, CustomerProductInfo product) {}

    static WeeklyRankingInfo notPublished(LocalDate periodStart) {
        return new WeeklyRankingInfo(periodStart, periodStart.plusWeeks(1), Status.NOT_PUBLISHED, null, null, null,
            List.of());
    }
}
