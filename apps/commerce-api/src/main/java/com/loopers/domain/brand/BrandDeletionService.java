package com.loopers.domain.brand;

import com.loopers.domain.product.ProductRepository;

import java.time.ZonedDateTime;

/**
 * 브랜드와 연결된 미삭제 상품의 논리 삭제를 조율한다.
 * 브랜드 잠금과 전체 트랜잭션, 최종 브랜드 저장은 호출 유스케이스가 책임진다.
 */
public class BrandDeletionService {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    public BrandDeletionService(BrandRepository brandRepository, ProductRepository productRepository) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
    }

    public Brand delete(long brandId, ZonedDateTime deletedAt) {
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new BrandDeletionException(BrandDeletionException.Reason.BRAND_NOT_FOUND));
        if (brand.isDeleted()) {
            return brand;
        }
        productRepository.softDeleteNonDeletedByBrandId(brandId, deletedAt);
        brand.delete(deletedAt);
        return brand;
    }
}
