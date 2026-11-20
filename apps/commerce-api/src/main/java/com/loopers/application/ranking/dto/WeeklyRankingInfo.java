package com.loopers.application.ranking.dto;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.RankingWeek;
import com.loopers.domain.ranking.WeeklyRankingSnapshot;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/** 요청한 주의 공개 랭킹. 공개본이 없으면 snapshot 정보 없이 NOT_PUBLISHED 다. */
public record WeeklyRankingInfo(
    Status status,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    Integer revision,
    ZonedDateTime frozenAt,
    List<Item> items
) {

    public enum Status {
        PUBLISHED,
        NOT_PUBLISHED
    }

    public record Item(int rank, Long productId, String name, long price, BrandSummary brand, long score) {

        public static Item of(RankedProduct ranked, Brand brand) {
            return new Item(
                ranked.ranking().getRanking(),
                ranked.product().getId(),
                ranked.product().getName(),
                ranked.product().getPrice().amount(),
                new BrandSummary(brand.getId(), brand.getName()),
                ranked.ranking().getScore()
            );
        }
    }

    public record BrandSummary(Long id, String name) {
    }

    public static WeeklyRankingInfo notPublished(RankingWeek week) {
        return new WeeklyRankingInfo(Status.NOT_PUBLISHED, week.periodStart(), week.periodEnd(), null, null, null, List.of());
    }

    public static WeeklyRankingInfo published(RankingWeek week, WeeklyRankingSnapshot snapshot, List<Item> items) {
        return new WeeklyRankingInfo(
            Status.PUBLISHED, week.periodStart(), week.periodEnd(),
            snapshot.getSnapshotId(), snapshot.getRevision(), snapshot.getFrozenAt(), items
        );
    }
}
