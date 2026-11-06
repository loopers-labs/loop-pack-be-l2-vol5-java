package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

// 상품 캐시에 담는 값. 브랜드 이름은 담지 않고 조회할 때 붙인다 — 브랜드 이름이 바뀌어도 그 브랜드 상품의 캐시를 버리지 않게
public record ProductSnapshot(Long id, Long brandId, String name, Long price, int stock, long likeCount) {
    public static ProductSnapshot from(ProductModel model, long likeCount) {
        return new ProductSnapshot(model.getId(), model.getBrandId(), model.getName(), model.getPrice(), model.getStock(), likeCount);
    }
}
