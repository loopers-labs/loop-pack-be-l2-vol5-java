package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProductFacade {

    private final BrandService brandService;
    private final ProductService productService;

    @Transactional
    public Product register(Long brandId, String name, Price price) {
        brandService.requireAvailable(brandId);
        return productService.register(brandId, name, price);
    }

    @Transactional
    public Product update(Long productId, String name, Price price) {
        return productService.update(productId, name, price);
    }

    @Transactional
    public void delete(Long productId) {
        productService.delete(productId);
    }

    @Transactional
    public Product adjustStock(Long productId, Quantity quantity) {
        return productService.adjustStock(productId, quantity);
    }
}
