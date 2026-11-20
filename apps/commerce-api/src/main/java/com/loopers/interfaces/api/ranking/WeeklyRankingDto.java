package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.dto.WeeklyRankingInfo;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

public class WeeklyRankingDto {

    public record BrandSummary(Long id, String name) {
    }

    public record ItemResponse(int rank, Long productId, String name, long price, BrandSummary brand, long score) {
        static ItemResponse from(WeeklyRankingInfo.Item item) {
            return new ItemResponse(
                item.rank(), item.productId(), item.name(), item.price(),
                new BrandSummary(item.brand().id(), item.brand().name()), item.score()
            );
        }
    }

    public record WeeklyRankingResponse(
        String status,
        LocalDate periodStart,
        LocalDate periodEnd,
        String snapshotId,
        Integer revision,
        ZonedDateTime frozenAt,
        List<ItemResponse> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(
                info.status().name(), info.periodStart(), info.periodEnd(),
                info.snapshotId(), info.revision(), info.frozenAt(),
                info.items().stream().map(ItemResponse::from).toList()
            );
        }
    }
}
