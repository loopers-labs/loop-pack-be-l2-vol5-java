package com.loopers.interfaces.api.product;

import com.loopers.domain.common.PageWindow;
import com.loopers.domain.product.ProductViewQuery;

import java.util.List;

public class ProductV1Dto {

    public record ProductResponse(
        Long id,
        String name,
        long price,
        boolean soldOut,
        Long brandId,
        String brandName,
        long likeCount,
        boolean liked
    ) {
        public static ProductResponse from(ProductViewQuery.View view) {
            return new ProductResponse(
                view.id(), view.name(), view.price(), view.soldOut(),
                view.brandId(), view.brandName(), view.likeCount(), view.liked());
        }
    }

    public record ProductPageResponse(List<ProductResponse> items, int page, int size, boolean hasNext) {
        public static ProductPageResponse of(PageWindow<ProductViewQuery.View> page, int pageNumber, int size) {
            return new ProductPageResponse(
                page.items().stream().map(ProductResponse::from).toList(), pageNumber, size, page.hasNext());
        }
    }
}
