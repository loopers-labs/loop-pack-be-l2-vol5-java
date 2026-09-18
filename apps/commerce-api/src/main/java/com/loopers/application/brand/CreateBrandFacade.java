package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class CreateBrandFacade {
    private final BrandRepository repository;

    public BrandInfo create(String name) { return BrandInfo.from(repository.save(Brand.create(name))); }
}
