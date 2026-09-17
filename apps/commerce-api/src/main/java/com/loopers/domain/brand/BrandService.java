package com.loopers.domain.brand;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand 만 주된 상태로 변경한다. 삭제 시 활성 Product 존재 여부를 읽어 BrandModel 에 전달하지만
 * Product 를 변경하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class BrandService {

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

    @Transactional
    public void delete(Long brandId) {
        BrandModel brand = findActive(brandId);
        brand.delete(productRepository.existsActiveByBrandId(brandId));
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
