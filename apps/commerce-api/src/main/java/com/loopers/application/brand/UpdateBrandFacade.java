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
@Transactional
public class UpdateBrandFacade {
    private final BrandRepository repository;

    public BrandInfo update(long id, String name) {
        Brand brand = repository.findById(id).orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
        brand.rename(name);
        return BrandInfo.from(repository.save(brand));
    }
}
