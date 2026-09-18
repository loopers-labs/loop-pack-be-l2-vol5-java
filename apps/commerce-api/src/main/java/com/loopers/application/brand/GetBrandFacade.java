package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetBrandFacade {
    private final BrandRepository repository;
    public BrandInfo customer(long id) {
        Brand brand = repository.findById(id).orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
        brand.requireActive();
        return BrandInfo.from(brand);
    }
}
