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

    @Transactional
    public BrandInfo register(String name) {
        Brand brand = new Brand(name);
        if (brandRepository.existsByName(brand.getName())) {
            throw new CoreException(ErrorType.CONFLICT, "이미 등록된 브랜드 이름입니다.");
        }

        Brand savedBrand = brandRepository.save(brand);
        return BrandInfo.from(savedBrand);
    }
}
