package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.WeeklyRankingInfo;
import com.loopers.domain.ranking.WeeklyRankingQueryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class WeeklyRankingV1Dto {

    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    public record WeeklyResponse(
        @Schema(allowableValues = {"NOT_PUBLISHED", "PUBLISHED"}) String status,
        @Schema(format = "date") String periodStart,
        @Schema(description = "다음 월요일, 구간에 포함하지 않음") String periodEnd,
        @Schema(description = "미공개이면 생략") String snapshotId,
        @Schema(format = "date-time", description = "입력 고정 UTC 시각. 미공개이면 생략") String inputFixedAt,
        List<ItemResponse> items
    ) {
        public static WeeklyResponse from(WeeklyRankingInfo info) {
            return new WeeklyResponse(info.status().name(), info.periodStart().toString(), info.periodEnd().toString(),
                info.snapshotId(), info.inputFixedAt() == null ? null : formatUtc(info.inputFixedAt()),
                info.items().stream().map(ItemResponse::from).toList());
        }
    }

    public record ItemResponse(
        Long productId,
        @Schema(description = "조회 시점의 현재 상품명") String productName,
        @Schema(type = "string", description = "구매 수량 합계. 크기와 관계없이 십진 문자열") String score,
        @Schema(description = "저장된 계산 순위. 제외된 상품이 있어도 다시 매기지 않음") Long rank
    ) {
        static ItemResponse from(WeeklyRankingQueryResult.Item item) {
            return new ItemResponse(item.productId(), item.productName(), item.score().toString(), item.rank());
        }
    }

    private static String formatUtc(LocalDateTime utcFields) {
        return utcFields.atOffset(ZoneOffset.UTC).format(UTC_MICROS);
    }
}
