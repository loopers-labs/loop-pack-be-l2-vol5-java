package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class RankingV1Dto {

    /**
     * periodEnd는 포함하지 않는 다음 월요일이다(UTC). rank는 계산 당시 순위라 비노출 상품이 빠진 자리에 빈칸이 있을 수 있다.
     */
    public record WeeklyRankingResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        String snapshotId,
        Integer revision,
        Instant inputFrozenAt,
        List<Item> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(info.periodStart(), info.periodEnd(), info.status().name(),
                info.snapshotId(), info.revision(), info.inputFrozenAt(),
                info.items().stream().map(Item::from).toList());
        }
    }

    public record Item(int rank, long score, Long productId, String productName, long price, Long brandId,
        String brandName) {
        static Item from(WeeklyRankingInfo.Item item) {
            var product = item.product();
            return new Item(item.rank(), item.score(), product.id(), product.name(), product.price(),
                product.brand().id(), product.brand().name());
        }
    }
}
