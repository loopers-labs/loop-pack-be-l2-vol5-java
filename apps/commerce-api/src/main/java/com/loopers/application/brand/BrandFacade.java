package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public BrandInfo getDetail(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        return BrandInfo.from(brand);
    }

    @Transactional
    public BrandInfo register(String name) {
        Brand brand = Brand.create(name);
        if (brandRepository.existsByName(brand.getName())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 등록된 브랜드 이름입니다.");
        }

        Brand savedBrand = brandRepository.save(brand);
        return BrandInfo.from(savedBrand);
    }
}
