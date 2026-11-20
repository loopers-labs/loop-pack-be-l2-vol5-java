package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.PublishedRanking.RankedProduct;
import com.loopers.application.ranking.WeeklyRanking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class WeeklyRankingDto {
    public record Response(String status, LocalDate periodStart, LocalDate periodEnd, String snapshotId,
            Instant frozenAt, List<Item> items) {
        public static Response from(WeeklyRanking ranking) {
            return new Response(ranking.status().name(), ranking.periodStart(), ranking.periodEnd(),
                    ranking.snapshotId(), ranking.frozenAt(), ranking.items().stream().map(Item::from).toList());
        }
    }

    public record Item(int rank, long productId, long score, String name, long price, Brand brand) {
        static Item from(RankedProduct product) {
            return new Item(product.rank(), product.productId(), product.score(), product.name(), product.price(),
                    new Brand(product.brandId(), product.brandName()));
        }
    }

    public record Brand(long brandId, String name) {
    }
}
