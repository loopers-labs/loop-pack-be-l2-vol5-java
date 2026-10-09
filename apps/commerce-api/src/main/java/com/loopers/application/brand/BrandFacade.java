package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class BrandFacade {
    private final BrandService brandService;
    private final ProductRepository productRepository;

    public BrandInfo create(String name, String description) {
        BrandModel brand = brandService.create(name, description);
        return BrandInfo.from(brand);
    }

    public BrandInfo get(Long id) {
        return BrandInfo.from(brandService.get(id));
    }

    public List<BrandInfo> getAll() {
        return brandService.getAllActive().stream().map(BrandInfo::from).toList();
    }

    public BrandInfo update(Long id, String name, String description) {
        return BrandInfo.from(brandService.update(id, name, description));
    }

    @Transactional
    public void delete(Long id) {
        brandService.delete(id);
        for (ProductModel product : productRepository.findActiveByBrandIdForUpdate(id)) {
            product.markDeleted();
            productRepository.save(product);
        }
    }
}
