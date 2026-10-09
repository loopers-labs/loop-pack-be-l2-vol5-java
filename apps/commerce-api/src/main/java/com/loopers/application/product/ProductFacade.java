package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final BrandService brandService;
    private final ProductService productService;

    @Transactional
    public ProductInfo create(Long brandId, String name, long price, int initialStockQuantity) {
        brandService.getForShare(brandId);
        return ProductInfo.from(productService.create(brandId, name, price, initialStockQuantity));
    }

    public ProductInfo get(Long id) {
        return ProductInfo.from(productService.get(id));
    }

    public List<ProductInfo> getAll() {
        return productService.findAllActive().stream().map(ProductInfo::from).toList();
    }

    public ProductInfo changeStock(Long id, int quantity) {
        return ProductInfo.from(productService.changeStock(id, quantity));
    }

    public ProductInfo update(Long id, String name, long price) {
        return ProductInfo.from(productService.update(id, name, price));
    }

    public void delete(Long id) {
        productService.delete(id);
    }
}
