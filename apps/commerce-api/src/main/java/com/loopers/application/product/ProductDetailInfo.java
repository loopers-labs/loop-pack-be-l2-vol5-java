package com.loopers.application.product;

// 고객 상품 목록·상세가 공유하는 조합 응답 — Product·Brand·Like, 서로 다른 세 aggregate를 조합한다.
// ProductInfo(Product 단독)와 별개인 이유: brand·likeCount는 ProductModel 하나만으로는 채울 수 없고
// application 계층에서 여러 aggregate를 조회해 조합해야 하는, 성격이 다른 응답이기 때문이다.
// 캐시가 붙은 뒤로는 캐시 값(CachedProduct·CachedBrand)에서 만든다 — DB에서 읽은 경우도 먼저 캐시 값으로 바꿔
// 한 경로로 조립하므로, 캐시 hit/miss와 관계없이 응답 필드·값이 같다 (docs/week4/design.md 6번 섹션).
public record ProductDetailInfo(
    Long id,
    String name,
    Long price,
    int remainingStock,
    BrandSummary brand,
    long likeCount
) {
    public record BrandSummary(Long id, String name) {
        public static BrandSummary from(CachedBrand brand) {
            return new BrandSummary(brand.id(), brand.name());
        }
    }

    public static ProductDetailInfo from(CachedProduct product, CachedBrand brand) {
        return new ProductDetailInfo(
            product.id(),
            product.name(),
            product.price(),
            product.remainingStock(),
            BrandSummary.from(brand),
            product.likeCount()
        );
    }
}
