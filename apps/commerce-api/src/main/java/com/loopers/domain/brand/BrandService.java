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

    /**
     * 살아 있는 브랜드를 공유 잠금으로 읽음. 상품 등록이 끝날 때까지 브랜드가 삭제되지 않게 함.
     * 같은 브랜드의 상품 등록끼리는 서로 막지 않음 (BRD-02 생성 입구, 3주차 설계 4.2)
     */
    @Transactional
    public Brand getActiveBrandForShare(Long brandId) {
        return brandRepository.findActiveForShare(brandId)
            .orElseThrow(() -> new CoreException(BrandErrorCode.BRAND_NOT_FOUND));
    }

    /** 잠금 읽기로 최신 상태를 봐서, 동시에 삭제된 브랜드를 오래된 값으로 되살리지 않음 (3주차 설계 4.2) */
    @Transactional
    public Brand update(Long brandId, String name, String description) {
        Brand brand = getActiveBrandForUpdate(brandId);
        brand.update(name, description);
        return brand;
    }

    /** 브랜드만 삭제한다. 그 브랜드의 상품을 함께 삭제하는 일은 BrandFacade 가 조율한다 (BRD-02 삭제 입구, 3주차 설계 2.2). */
    @Transactional
    public void delete(Long brandId) {
        getActiveBrandForUpdate(brandId).delete();
    }

    private Brand getActiveBrandForUpdate(Long brandId) {
        return brandRepository.findActiveForUpdate(brandId)
            .orElseThrow(() -> new CoreException(BrandErrorCode.BRAND_NOT_FOUND));
    }
}
