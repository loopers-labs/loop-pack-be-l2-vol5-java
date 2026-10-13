package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductAdminQuery;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductViewQuery;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProductFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final ProductViewQuery productViewQuery;
    private final ProductAdminQuery productAdminQuery;

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

    public PageWindow<ProductViewQuery.View> findPage(ProductViewQuery.Criteria criteria) {
        return productViewQuery.findPage(criteria);
    }

    public ProductViewQuery.View get(Long productId, Long viewerId) {
        return productViewQuery.findAliveById(productId, viewerId)
            .orElseThrow(() -> new DomainException(DomainError.PRODUCT_NOT_FOUND));
    }

    public PageWindow<ProductAdminQuery.View> findPageForAdmin(
        Long brandId, ProductAdminQuery.Sort sort, PageNumber page, PageSize size) {
        return productAdminQuery.findPage(brandId, sort, page, size);
    }

    public Product getForAdmin(Long productId) {
        return productService.get(productId);
    }
}
