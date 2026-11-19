package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class RankingV1Dto {

    /** API 표시용 UTC 초 단위. checksum 의 6자리(D13)와 다른 용도다. */
    private static final DateTimeFormatter FROZEN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public record WeeklyRankingResponse(
        String status,
        String periodStart,
        String periodEnd,
        String snapshotId,
        String frozenAt,
        List<Item> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(
                info.status().name(),
                info.periodStart().toString(),
                info.periodEnd().toString(),
                info.snapshotId(),
                format(info.frozenAt()),
                info.items().stream().map(Item::from).toList()
            );
        }

        private static String format(LocalDateTime frozenAt) {
            return frozenAt == null ? null : FROZEN_AT.format(frozenAt);
        }
    }

    public record Item(long productId, String productName, long score, int ranking) {
        /** Facade 가 R4 를 검증한 뒤라 ranking 은 NULL 이 아니다. */
        public static Item from(WeeklyRankingInfo.Item item) {
            return new Item(item.productId(), item.productName(), item.score(), item.ranking());
        }
    }
}
