package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;

// 고객 상품 목록·상세가 공유하는 조합 응답 — Product·Brand·Like, 서로 다른 세 aggregate를 조합한다.
// ProductInfo(Product 단독)와 별개인 이유: brand·likeCount는 ProductModel 하나만으로는 채울 수 없고
// application 계층에서 여러 aggregate를 조회해 조합해야 하는, 성격이 다른 응답이기 때문이다.
public record ProductDetailInfo(
    Long id,
    String name,
    Long price,
    int remainingStock,
    BrandSummary brand,
    long likeCount
) {
    public record BrandSummary(Long id, String name) {
        public static BrandSummary from(BrandModel model) {
            return new BrandSummary(model.getId(), model.getName());
        }
    }

    public static ProductDetailInfo of(ProductModel product, BrandModel brand, long likeCount) {
        return new ProductDetailInfo(
            product.getId(),
            product.getName(),
            product.getPrice(),
            product.getRemainingStock(),
            BrandSummary.from(brand),
            likeCount
        );
    }
}
