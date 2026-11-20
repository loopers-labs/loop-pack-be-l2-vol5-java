package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class RankingV1Dto {

    /**
     * rank는 계산 당시 순위다. 삭제·비노출 상품은 빠지고 순위를 다시 매기지 않으므로 1, 3처럼 간격이 있을 수 있다.
     * status NOT_PUBLISHED는 그 주의 공개본이 아직 없다는 뜻이고, PUBLISHED에 빈 items는 구매가 없던 주의 완성본이다.
     */
    public record WeeklyRankingResponse(
        String status,
        LocalDate periodStart,
        LocalDate periodEnd,
        String snapshotId,
        Integer revision,
        Instant frozenAt,
        List<Item> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(
                info.status().name(), info.periodStart(), info.periodEnd(), info.snapshotId(), info.revision(), info.frozenAt(),
                info.items().stream().map(Item::from).toList()
            );
        }
    }

    public record Item(int rank, long productId, long score, String name, String brandName, Long price) {
        static Item from(WeeklyRankingInfo.Item item) {
            return new Item(item.rank(), item.productId(), item.score(), item.name(), item.brandName(), item.price());
        }
    }
}
