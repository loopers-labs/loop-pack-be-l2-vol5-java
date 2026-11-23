package com.loopers.application.product.dto;

import com.loopers.domain.product.vo.RankedProductView;
import com.loopers.domain.product.vo.WeeklyRankingView;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/** 공개본이 없으면 published 가 false 이고 snapshotId · frozenAt 이 비어 있다 (9주차 D-189) */
public record WeeklyRankingInfo(
    boolean published,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    ZonedDateTime frozenAt,
    List<RankedProductView> items
) {
    public static WeeklyRankingInfo from(WeeklyRankingView view) {
        return switch (view) {
            case WeeklyRankingView.NotPublished notPublished ->
                new WeeklyRankingInfo(false, notPublished.periodStart(), notPublished.periodEnd(), null, null, List.of());
            case WeeklyRankingView.Published published -> new WeeklyRankingInfo(
                true, published.periodStart(), published.periodEnd(), published.snapshotId(), published.frozenAt(), published.items());
        };
    }
}
