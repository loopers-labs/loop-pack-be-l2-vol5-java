package com.loopers.interfaces.api.ranking;

import com.loopers.application.ranking.query.WeeklyRankingView;
import com.loopers.interfaces.api.brand.BrandV1Dto;
import com.loopers.interfaces.api.product.ProductV1Dto;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

public class RankingV1Dto {
    public static final String PUBLISHED = "PUBLISHED";
    public static final String NOT_PUBLISHED = "NOT_PUBLISHED";

    /**
     * 설계 10-6. status 가 NOT_PUBLISHED 면 snapshotId·revision·frozenAt 은 없고(null 생략) items 는 빈 배열이다.
     * periodEnd 는 제외 경계(다음 월요일). frozenAt 은 UTC.
     */
    public record WeeklyRankingResponse(
        String status, LocalDate periodStart, LocalDate periodEnd, String snapshotId, Long revision, ZonedDateTime frozenAt,
        List<Item> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingView.Weekly view) {
            if (!view.published()) {
                return new WeeklyRankingResponse(NOT_PUBLISHED, view.periodStart(), view.periodEnd(), null, null, null, List.of());
            }
            WeeklyRankingView.Publication publication = view.publication();
            return new WeeklyRankingResponse(PUBLISHED, view.periodStart(), view.periodEnd(), publication.snapshotId(),
                publication.revision(), publication.frozenAtUtc().atZone(ZoneOffset.UTC),
                view.items().stream().map(Item::from).toList());
        }
    }

    /** rank 는 저장된 계산 순위다. 제외된 상품 자리는 비어 1·3위처럼 간격이 있을 수 있다. product 는 고객 상품 요약(ProductSummary)과 같다. */
    public record Item(int rank, long score, ProductV1Dto.ProductResponse product) {
        public static Item from(WeeklyRankingView.Entry entry) {
            return new Item(entry.rank(), entry.score(), new ProductV1Dto.ProductResponse(
                entry.productId(), entry.name(), entry.price(), new BrandV1Dto.BrandResponse(entry.brandId(), entry.brandName()),
                entry.likeCount()));
        }
    }
}
