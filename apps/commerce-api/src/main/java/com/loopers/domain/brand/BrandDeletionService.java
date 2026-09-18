package com.loopers.domain.brand;

import com.loopers.domain.product.ProductRepository;

import java.time.ZonedDateTime;

/**
 * 조회 결과로 삭제 조건을 판단하고 도메인 상태를 변경한다.
 * DB 조회의 동시성 보호와 결과 저장은 이 기능을 연결하는 유스케이스가 책임진다.
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
        if (productRepository.existsNonDeletedByBrandId(brandId)) {
            throw new BrandDeletionException(BrandDeletionException.Reason.NON_DELETED_PRODUCTS_EXIST);
        }
        brand.delete(deletedAt);
        return brand;
    }
}
