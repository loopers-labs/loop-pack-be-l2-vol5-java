package com.loopers.application.brand;

import com.loopers.application.brand.port.BrandRepository;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.brand.Brand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandApplicationService {
    private final BrandRepository brandRepository;

    public BrandApplicationService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @Transactional
    public BrandResult create(String name) {
        return BrandResult.from(brandRepository.save(Brand.create(name)));
    }

    @Transactional(readOnly = true)
    public BrandResult getBrand(BrandId id) {
        Brand brand = brandRepository.findById(id)
            .filter(found -> !found.isDeleted())
            .orElseThrow(BrandNotFoundException::new);
        return BrandResult.from(brand);
    }
}
