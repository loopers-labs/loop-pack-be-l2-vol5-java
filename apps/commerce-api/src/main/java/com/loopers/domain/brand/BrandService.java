package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    /** 없거나 삭제된 브랜드는 BRAND_NOT_FOUND. 다른 도메인도 이 조회를 쓴다 (설계 D-31). */
    @Transactional(readOnly = true)
    public Brand getActiveBrand(Long brandId) {
        return brandRepository.findActive(brandId)
            .orElseThrow(() -> new CoreException(BrandErrorCode.BRAND_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<Brand> getActiveBrands(Pageable pageable) {
        return brandRepository.findActive(pageable);
    }

    @Transactional
    public Brand create(String name, String description) {
        return brandRepository.save(new Brand(name, description));
    }

    @Transactional
    public Brand update(Long brandId, String name, String description) {
        Brand brand = getActiveBrand(brandId);
        brand.update(name, description);
        return brand;
    }

    /** 삭제되지 않은 상품이 남은 브랜드는 삭제하지 않는다 (BRD-02 삭제 입구). */
    @Transactional
    public void delete(Long brandId) {
        Brand brand = getActiveBrand(brandId);
        if (brandRepository.hasActiveProduct(brandId)) {
            throw new CoreException(BrandErrorCode.BRAND_HAS_PRODUCTS);
        }
        brand.delete();
    }
}
