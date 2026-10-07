package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/** Brand API 유스케이스의 처리 순서와 트랜잭션 경계를 담당한다. */
@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    @Transactional
    public BrandModel create(String name) {
        return brandRepository.save(BrandModel.create(name));
    }

    @Transactional(readOnly = true)
    public BrandModel getBrand(Long brandId) {
        return findActive(brandId);
    }

    @Transactional
    public BrandModel update(Long brandId, String name) {
        BrandModel brand = findActive(brandId);
        brand.updateName(name);
        return brandRepository.save(brand);
    }

    /**
     * 브랜드와 연결된 미삭제 상품을 같은 트랜잭션에서 함께 삭제한다.
     * 상품은 bulk UPDATE 한 번으로 처리하고, 중간 예외는 삼키지 않고 전파해 전체를 rollback 한다.
     */
    @Transactional
    public void delete(Long brandId) {
        BrandModel brand = findActive(brandId);
        productRepository.softDeleteAllActiveByBrandId(brandId, ZonedDateTime.now());
        brand.delete();
        brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public PageResult<BrandModel> getBrands(PageCommand page, ListSort sort) {
        return brandRepository.findActivePage(page, sort);
    }

    private BrandModel findActive(Long brandId) {
        return brandRepository.findActive(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
    }
}
