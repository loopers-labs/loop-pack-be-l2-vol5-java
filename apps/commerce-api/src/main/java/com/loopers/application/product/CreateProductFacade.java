package com.loopers.application.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class CreateProductFacade {
    private final ProductRepository repository;
    private final BrandRepository brands;

    public ProductInfo create(Long brandId, String name, Long price) {
        if (brandId == null || brandId <= 0 || price == null) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        brands.findById(brandId).orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND)).requireActive();
        return ProductInfo.from(repository.save(Product.create(brandId, name, price)));
    }
}
