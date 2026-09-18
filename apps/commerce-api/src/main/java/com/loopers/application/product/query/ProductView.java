package com.loopers.application.product.query;

public record ProductView(long productId, String name, long price, BrandView brand, long likeCount) {
    public record BrandView(long brandId, String name) {}
}
