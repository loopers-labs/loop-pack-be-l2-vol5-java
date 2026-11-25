package com.loopers.ranking.adapter.in.web.dto;

import com.loopers.ranking.application.port.in.WeeklyRankingInfo;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

public class RankingDto {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /**
     * 주간 랭킹 응답 (W9 D-6). frozenAt은 입력 고정 시점(UTC) — "이 시점까지의 구매 기준"이다.
     */
    public record WeeklyRankingResponse(
        String status,
        LocalDate periodStart,
        LocalDate periodEnd,
        String snapshotId,
        ZonedDateTime frozenAt,
        List<Item> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(
                info.status().name(),
                info.periodStart(),
                info.periodEnd(),
                info.snapshotId(),
                info.frozenAt() == null ? null : info.frozenAt().withZoneSameInstant(ZoneOffset.UTC),
                info.items().stream().map(Item::from).toList()
            );
        }
    }

    /**
     * 순위 항목. 표시 정보(이름·가격·브랜드)는 고객 상품 응답과 같은 규칙이다. 좋아요 수는 넣지 않는다 (D-6).
     */
    public record Item(int rank, Long productId, long score, String name, long price, BrandSummary brand) {
        static Item from(WeeklyRankingInfo.Item item) {
            return new Item(item.rank(), item.productId(), item.score(), item.name(), item.price(), new BrandSummary(item.brandId(), item.brandName()));
        }
    }

    public record BrandSummary(Long id, String name) {}

    /**
     * periodStart는 필수, yyyy-MM-dd, 월요일 (D-6). 그 밖은 400.
     */
    public static LocalDate parsePeriodStart(String periodStart) {
        if (periodStart == null || periodStart.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 필수입니다. (예: 2026-08-17)");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(periodStart);
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 yyyy-MM-dd 형식이어야 합니다. [periodStart = " + periodStart + "]");
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 주의 시작인 월요일이어야 합니다. [periodStart = " + periodStart + "]");
        }
        return date;
    }

    /**
     * limit은 1–100, 기본 20 (상품 목록 size 규칙 P-08과 같음). 범위 밖이면 400.
     */
    public static int parseLimit(Integer limit) {
        int resolved = limit == null ? DEFAULT_LIMIT : limit;
        if (resolved < 1 || resolved > MAX_LIMIT) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit은 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
        }
        return resolved;
    }
}
