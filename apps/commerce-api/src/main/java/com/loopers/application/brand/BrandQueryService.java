package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BrandQueryService {

    private final BrandRepository brandRepository;

    public BrandQueryService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    /**
     * 고객에게 노출할 수 있는 브랜드의 상세 정보를 조회한다.
     */
    public BrandInfo getDetail(long brandId) {
        Brand brand = brandRepository.findById(brandId)
            .filter(candidate -> !candidate.isDeleted())
            .orElseThrow(() -> new BrandQueryException(BrandQueryException.Reason.BRAND_NOT_FOUND));
        return new BrandInfo(brand.getId(), brand.getName());
    }
}
