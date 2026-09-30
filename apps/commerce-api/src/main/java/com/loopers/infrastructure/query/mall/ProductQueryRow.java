package com.loopers.infrastructure.query.mall;

import com.loopers.application.mall.query.AdminProductView;
import com.loopers.application.mall.query.BrandSummaryView;
import com.loopers.application.mall.query.ProductDetailView;
import com.loopers.application.mall.query.ProductSummaryView;
import java.time.Instant;

// QueryDSL 상품 조회 결과 행
public record ProductQueryRow(long productId, String name, long price, long brandId, String brandName,
                              long likeCount, String description, int stock, Instant createdAt) {
    ProductSummaryView toSummary() {
        return new ProductSummaryView(productId, name, price, brand(), likeCount);
    }

    ProductDetailView toDetail() {
        return new ProductDetailView(productId, name, price, brand(), likeCount, description, stock);
    }

    AdminProductView toAdminProduct() {
        return new AdminProductView(productId, name, price, brand(), likeCount, description, stock, createdAt);
    }

    private BrandSummaryView brand() {
        return new BrandSummaryView(brandId, brandName);
    }
}
