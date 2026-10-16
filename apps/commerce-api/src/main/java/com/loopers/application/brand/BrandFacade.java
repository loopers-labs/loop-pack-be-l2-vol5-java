package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.transaction.AfterCommitRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/** Brand API 유스케이스의 처리 순서와 트랜잭션 경계를 담당한다. */
@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductDetailCache productDetailCache;
    private final AfterCommitRunner afterCommitRunner;

    @Transactional
    public BrandModel create(String name) {
        return brandRepository.save(BrandModel.create(name));
    }

    @Transactional(readOnly = true)
    public BrandModel getBrand(Long brandId) {
        return findActive(brandId);
    }

    /** 브랜드 이름은 연결된 상품들의 상세 기본정보에 포함되므로 commit 뒤 해당 상품들의 기본정보를 무효화한다. */
    @Transactional
    public BrandModel update(Long brandId, String name) {
        BrandModel brand = findActive(brandId);
        brand.updateName(name);
        BrandModel updated = brandRepository.save(brand);
        evictDetailAfterCommit(productRepository.findActiveIdsByBrandId(brandId));
        return updated;
    }

    /**
     * 브랜드와 연결된 미삭제 상품을 같은 트랜잭션에서 함께 삭제한다.
     * 상품은 bulk UPDATE 한 번으로 처리하고, 중간 예외는 삼키지 않고 전파해 전체를 rollback 한다.
     * 삭제 대상 상품의 상세 기본정보는 bulk 전에 식별자를 확인해 commit 뒤 무효화한다.
     */
    @Transactional
    public void delete(Long brandId) {
        BrandModel brand = findActive(brandId);
        List<Long> productIds = productRepository.findActiveIdsByBrandId(brandId);
        productRepository.softDeleteAllActiveByBrandId(brandId, ZonedDateTime.now());
        brand.delete();
        brandRepository.save(brand);
        evictDetailAfterCommit(productIds);
    }

    @Transactional(readOnly = true)
    public PageResult<BrandModel> getBrands(PageCommand page, ListSort sort) {
        return brandRepository.findActivePage(page, sort);
    }

    private void evictDetailAfterCommit(List<Long> productIds) {
        afterCommitRunner.runAfterCommit("상품 상세 기본정보 무효화", () -> productDetailCache.evictAll(productIds));
    }

    private BrandModel findActive(Long brandId) {
        return brandRepository.findActive(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
    }
}
