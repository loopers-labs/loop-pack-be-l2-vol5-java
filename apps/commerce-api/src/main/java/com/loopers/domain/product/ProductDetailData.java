package com.loopers.domain.product;

/**
 * 고객 상품 상세를 캐시 값과 매 조회의 DB 결과로 나눠 읽을 때 쓰는 값 타입.
 * 최종 응답은 application 이 두 값을 합성한 {@link ProductQueryResult} 다.
 */
public final class ProductDetailData {

    private ProductDetailData() {
    }

    /**
     * 공용 캐시에 보관하는 기본정보. 상품명·브랜드 이름·가격·현재 재고만 담는다.
     * 좋아요 수와 상품·브랜드 노출 여부는 담지 않고 매 조회마다 DB 에서 확인한다.
     */
    public record CacheValue(
        String name,
        String brandName,
        long price,
        long stockQuantity
    ) {
        public static CacheValue from(ProductQueryResult detail) {
            return new CacheValue(detail.name(), detail.brandName(), detail.price(), detail.stockQuantity());
        }
    }

    /**
     * 캐시 hit 에서도 매번 DB 에서 읽는 값. 상품과 브랜드가 모두 삭제되지 않았을 때만 존재하며,
     * 상품·브랜드 식별자와 DB 에서 집계한 좋아요 수를 담는다.
     */
    public record DbResult(
        Long id,
        Long brandId,
        long likeCount
    ) {
    }
}
