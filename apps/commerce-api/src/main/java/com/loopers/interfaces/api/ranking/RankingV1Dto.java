package com.loopers.interfaces.api.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.loopers.domain.ranking.WeekStart;
import com.loopers.domain.ranking.WeeklyRankingQuery;

public class RankingV1Dto {

    public enum Status {
        PUBLISHED,
        NOT_PUBLISHED
    }

    public record WeeklyResponse(
        Status status,
        LocalDate periodStart,
        LocalDate periodEnd,
        String snapshotId,
        LocalDateTime frozenAt,
        List<ItemResponse> items
    ) {
        public static WeeklyResponse of(WeekStart week, Optional<WeeklyRankingQuery.Published> published) {
            return published
                .map(found -> new WeeklyResponse(
                    Status.PUBLISHED, week.value(), week.end(), found.snapshotId(), found.frozenAt(),
                    found.items().stream().map(ItemResponse::from).toList()
                ))
                .orElseGet(() -> new WeeklyResponse(
                    Status.NOT_PUBLISHED, week.value(), week.end(), null, null, List.of()
                ));
        }
    }

    public record ItemResponse(
        int rank,
        long productId,
        long score,
        String productName,
        Long brandId,
        String brandName,
        long price,
        boolean soldOut
    ) {
        static ItemResponse from(WeeklyRankingQuery.Item item) {
            return new ItemResponse(
                item.rank(), item.productId(), item.score(), item.productName(),
                item.brandId(), item.brandName(), item.price(), item.soldOut()
            );
        }
    }
}
