package com.loopers.interfaces.api.product;

import com.loopers.application.product.dto.WeeklyRankingInfo;
import com.loopers.domain.product.vo.RankedProductView;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

public class WeeklyRankingV1Dto {

    public enum PublicationStatus {
        PUBLISHED,
        NOT_PUBLISHED
    }

    /** 공개본이 없으면 NOT_PUBLISHED 와 빈 items. 구매가 없던 공개본은 PUBLISHED 와 snapshotId · 빈 items (실습 §1) */
    public record WeeklyRankingResponse(
        PublicationStatus status,
        LocalDate periodStart,
        LocalDate periodEnd,
        String snapshotId,
        ZonedDateTime frozenAt,
        List<RankedProductResponse> items
    ) {
        public static WeeklyRankingResponse from(WeeklyRankingInfo info) {
            return new WeeklyRankingResponse(
                info.published() ? PublicationStatus.PUBLISHED : PublicationStatus.NOT_PUBLISHED,
                info.periodStart(),
                info.periodEnd(),
                info.snapshotId(),
                info.frozenAt(),
                info.items().stream().map(RankedProductResponse::from).toList()
            );
        }
    }

    /** rank 는 계산 당시 순위라 삭제 상품을 뺀 자리가 비어 1 · 3 처럼 이어지지 않을 수 있다 (9주차 D-188) */
    public record RankedProductResponse(int rank, long score, Long productId, String name, long price, ProductV1Dto.BrandSummary brand) {
        public static RankedProductResponse from(RankedProductView view) {
            return new RankedProductResponse(
                view.ranking(),
                view.score(),
                view.productId(),
                view.name(),
                view.price(),
                new ProductV1Dto.BrandSummary(view.brandId(), view.brandName())
            );
        }
    }
}
