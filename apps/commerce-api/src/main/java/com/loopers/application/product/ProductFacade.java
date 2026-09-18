package com.loopers.application.product;

import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    private final ProductService productService;

    /** 고객 쪽 brandId 는 조회 조건이다. 없는 브랜드면 빈 목록이 된다 (설계 D-24). */
    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        return productService.getActiveProductViews(brandId, sort, pageable).map(ProductInfo::from);
    }

    public ProductInfo getProduct(Long productId) {
        return ProductInfo.from(productService.getActiveProductView(productId));
    }
}
