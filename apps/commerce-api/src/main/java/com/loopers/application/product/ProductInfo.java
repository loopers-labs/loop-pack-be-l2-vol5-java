package com.loopers.application.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductView;

public record ProductInfo(
    Long id,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    String brandDescription
) {
    public static ProductInfo from(ProductView view) {
        return new ProductInfo(
            view.id(),
            view.name(),
            view.price(),
            view.stock(),
            view.likeCount(),
            view.brandId(),
            view.brandName(),
            view.brandDescription()
        );
    }

    /** 고객에게는 재고 수량 대신 품절 여부만 내보낸다 (설계 2.4, 4주차 cache-design.md 3.3) */
    public boolean soldOut() {
        return Product.isSoldOut(stock);
    }
}
