package com.loopers.application.product.query;

/**
 * 고객 상품 조회 캐시에 담는 값 (DR-37). 좋아요 수는 담지 않고 읽을 때 TB-08 에서 붙인다.
 * 모양이 바뀌면 캐시 키의 스키마 버전을 올려 옛 모양의 값을 읽지 않게 한다.
 */
public final class ProductCacheValue {
    private ProductCacheValue() {
    }

    /** FR-PRODUCT-01/02 상품 한 건. {@link ProductView.Summary} 에서 좋아요 수를 뺀 것. */
    public record Item(Long id, String name, Long price, Long brandId, String brandName) {
        public static Item from(ProductView.Summary summary) {
            return new Item(summary.id(), summary.name(), summary.price(), summary.brandId(), summary.brandName());
        }

        public ProductView.Summary withLikeCount(long likeCount) {
            return new ProductView.Summary(id, name, price, brandId, brandName, likeCount);
        }
    }
}
