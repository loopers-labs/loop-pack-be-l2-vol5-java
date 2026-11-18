package com.loopers.domains.ranking.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// rank는 표시 순위(삭제 상품을 뺀 뒤 1부터)다. 저장된 계산 순위는 응답에 싣지 않는다(D157).
public record WeeklyRankingResult(String status, LocalDate periodStart, LocalDate periodEnd, String snapshotId, Instant frozenAt,
    List<Item> items) {
    public static final String PUBLISHED = "PUBLISHED";
    public static final String NOT_PUBLISHED = "NOT_PUBLISHED";

    public record Item(int rank, long productId, String name, long price, long brandId, String brandName, long score,
        boolean soldOut) { }
}
