package com.loopers.interfaces.api.product;

import com.loopers.domain.product.ProductViewQuery;

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
}
