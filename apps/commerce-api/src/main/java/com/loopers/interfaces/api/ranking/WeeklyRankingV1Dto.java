package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;
import com.loopers.application.ranking.WeeklyRankingStatus;
import com.loopers.domain.ranking.RankedProduct;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class WeeklyRankingV1Dto {

    public record WeeklyRankingResponse(
        WeeklyRankingStatus status,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer revision,
        Instant calculatedAt,
        List<Item> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(info.status(), info.periodStart(), info.periodEnd(), info.revision(),
                info.calculatedAt(), info.items().stream().map(Item::from).toList());
        }
    }

    public record Item(
        int rank,
        long productId,
        String productName,
        long brandId,
        String brandName,
        long price,
        long score
    ) {
        public static Item from(RankedProduct product) {
            return new Item(product.rank(), product.productId(), product.productName(), product.brandId(),
                product.brandName(), product.price(), product.score());
        }
    }
}
