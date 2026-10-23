package com.loopers.application.product.dto;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;

/**
 * 고객 상품 목록의 항목. DB 조회와 캐시 hit 가 같은 타입을 돌려주도록, 캐시에 담는 필드({@code CachedProductListInfo})에
 * 좋아요 수만 더한 모양이다. 재고·생성/수정 시각은 고객 목록에 쓰지 않아 담지 않는다(재고는 주문마다 바뀌어 캐시할 수 없다).
 *
 * <p>고객 목록에 필드를 더하려면 캐시 payload 와 형식 버전도 함께 바꿔야 한다.
 */
public record ProductListItemInfo(Long id, String name, long price, BrandSummary brand, long likeCount) {

    public record BrandSummary(Long id, String name) {
    }

    public static ProductListItemInfo of(Product product, Brand brand, long likeCount) {
        return new ProductListItemInfo(
            product.getId(),
            product.getName(),
            product.getPrice().amount(),
            new BrandSummary(brand.getId(), brand.getName()),
            likeCount
        );
    }
}
