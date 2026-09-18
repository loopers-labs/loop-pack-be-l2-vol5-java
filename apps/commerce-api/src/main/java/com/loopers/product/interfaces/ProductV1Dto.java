package com.loopers.product.interfaces;

import com.loopers.brand.domain.Brand;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.Product;

public class ProductV1Dto {

    public record CreateProductRequest(Long brandId, String name, Long price) {
    }

    public record UpdateProductRequest(String name, Long price, Long brandId) {
    }

    public record StockRequest(Integer quantity) {
    }

    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(Brand brand) {
            return new BrandResponse(brand.getId(), brand.getName());
        }
    }

    public record CustomerProductResponse(
        Long id,
        String name,
        long price,
        BrandResponse brand,
        long likeCount,
        boolean soldOut
    ) {
        public static CustomerProductResponse from(ProductUseCase.CustomerProduct view) {
            Product product = view.product();
            return new CustomerProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                BrandResponse.from(view.brand()),
                view.likeCount(),
                product.getStock().quantity() == 0
            );
        }
    }

    public record AdminProductResponse(
        Long id,
        String name,
        long price,
        BrandResponse brand,
        int stock,
        boolean deleted
    ) {
        public static AdminProductResponse from(ProductUseCase.AdminProduct view) {
            Product product = view.product();
            return new AdminProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                BrandResponse.from(view.brand()),
                product.getStock().quantity(),
                product.isDeleted()
            );
        }
    }
}
