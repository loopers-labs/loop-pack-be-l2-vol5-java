package com.loopers.application.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 조회 결과. NOT_PUBLISHED 면 snapshotId·frozenAt 은 null, items 는 빈 목록. */
public record WeeklyRankingInfo(
    Status status,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    LocalDateTime frozenAt,
    List<Item> items
) {
    public enum Status { PUBLISHED, NOT_PUBLISHED }

    /** ranking 은 배치가 저장한 계산 순위 그대로. 삭제 상품이 빠져 간격이 생길 수 있다. */
    public record Item(long productId, String productName, long score, int ranking) {}
}
