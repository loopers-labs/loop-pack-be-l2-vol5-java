package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    @Transactional
    public BrandInfo create(String name, String description) {
        BrandModel saved = brandRepository.save(new BrandModel(name, description));
        return BrandInfo.from(saved);
    }

    @Transactional(readOnly = true)
    public BrandInfo getBrand(Long brandId) {
        return BrandInfo.from(getActiveBrand(brandId));
    }

    @Transactional(readOnly = true)
    public Page<BrandInfo> getBrands(Pageable pageable) {
        return brandRepository.findActive(pageable).map(BrandInfo::from);
    }

    @Transactional
    public BrandInfo update(Long brandId, String name, String description) {
        BrandModel brand = getActiveBrand(brandId);
        brand.update(name, description);
        return BrandInfo.from(brand);
    }

    /**
     * BRD-02: 삭제되지 않은 상품(재고 0 포함)이 남은 브랜드는 삭제할 수 없다.
     * 연결 상품 확인은 상품 저장소가 답하고, 삭제 행동은 브랜드가 한다.
     */
    @Transactional
    public void delete(Long brandId) {
        BrandModel brand = getActiveBrand(brandId);
        if (productRepository.existsActiveByBrandId(brandId)) {
            throw new CoreException(ErrorType.CONFLICT, "삭제되지 않은 상품이 있는 브랜드는 삭제할 수 없습니다.");
        }
        brand.delete();
    }

    private BrandModel getActiveBrand(Long brandId) {
        return brandRepository.findActiveById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
    }
}
