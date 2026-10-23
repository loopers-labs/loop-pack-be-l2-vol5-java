package com.loopers.application.product.dto;

import com.loopers.application.brand.dto.BrandInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;

/**
 * 고객 상품 조회 결과. 상품에 브랜드 정보와 좋아요 수를 조합한 것으로, 어떤 필드를 응답에 담을지는 Dto 가 고른다.
 *
 * <p>상품이 있는 브랜드는 삭제할 수 없으므로 {@code brand} 는 항상 존재한다. 브랜드를 찾지 못하는 깨진 상태는
 * 조합하기 전에 {@code ProductFacade} 가 거절한다.
 */
public record ProductDetailInfo(ProductInfo product, BrandInfo brand, long likeCount) {

    public static ProductDetailInfo of(Product product, Brand brand, long likeCount) {
        return new ProductDetailInfo(ProductInfo.from(product), BrandInfo.from(brand), likeCount);
    }
}
