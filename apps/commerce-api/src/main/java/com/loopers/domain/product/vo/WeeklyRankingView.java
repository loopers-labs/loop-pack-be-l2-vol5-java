package com.loopers.domain.product.vo;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 요청한 주의 랭킹. 공개본이 없으면 snapshot 정보 없이 빈 목록, 공개본이 있으면 항목이 0개여도 snapshot 정보를 준다 (9주차 D-189)
 */
public sealed interface WeeklyRankingView {

    LocalDate periodStart();

    default LocalDate periodEnd() {
        return periodStart().plusDays(7);
    }

    record NotPublished(LocalDate periodStart) implements WeeklyRankingView {}

    record Published(
        LocalDate periodStart,
        String snapshotId,
        ZonedDateTime frozenAt,
        List<RankedProductView> items
    ) implements WeeklyRankingView {
        public Published {
            items = List.copyOf(items);
        }
    }
}
