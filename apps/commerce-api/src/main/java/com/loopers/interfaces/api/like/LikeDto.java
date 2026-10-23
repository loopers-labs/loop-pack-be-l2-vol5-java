package com.loopers.interfaces.api.like;

import com.loopers.application.product.dto.ProductInfo;

public class LikeDto {
    // 내 좋아요 목록의 항목. 재고와 생성·수정 시각은 담지 않는다. 고객 상품 응답이 정해지면 그에 맞춰 통합한다.
    public record LikedProductResponse(Long id, Long brandId, String name, long price) {
        public static LikedProductResponse from(ProductInfo info) {
            return new LikedProductResponse(info.id(), info.brandId(), info.name(), info.price());
        }
    }
}
