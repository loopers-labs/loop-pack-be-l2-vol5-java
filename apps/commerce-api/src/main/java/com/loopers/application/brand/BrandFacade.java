package com.loopers.application.brand;

import com.loopers.application.brand.dto.BrandInfo;
import com.loopers.domain.brand.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객용 브랜드 유스케이스. 요청자에 따라 결과가 달라지지 않는 카탈로그 조회라 요청자를 식별하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandService brandService;

    @Transactional(readOnly = true)
    public BrandInfo get(Long brandId) {
        return BrandInfo.from(brandService.get(brandId));
    }
}
