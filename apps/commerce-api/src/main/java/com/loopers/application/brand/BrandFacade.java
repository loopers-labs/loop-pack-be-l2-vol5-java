package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class BrandFacade {

    private final BrandService brandService;

    @Transactional
    public Brand register(String name, String description) {
        return brandService.register(name, description);
    }

    @Transactional
    public Brand update(Long brandId, String name, String description) {
        return brandService.update(brandId, name, description);
    }

    @Transactional
    public void delete(Long brandId) {
        brandService.delete(brandId);
    }

    public Brand get(Long brandId) {
        return brandService.get(brandId);
    }

    public PageWindow<Brand> findPage(PageNumber page, PageSize size) {
        return brandService.findPage(page, size);
    }
}
