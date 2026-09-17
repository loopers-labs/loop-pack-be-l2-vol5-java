package com.loopers.application.product;

import com.loopers.application.PageInfo;
import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.PageCondition;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;
    private final ProductInfoAssembler productInfoAssembler;

    public ProductInfo getProduct(Long productId) {
        Product product = productService.getProduct(productId);
        BrandInfo brand = BrandInfo.from(brandService.getBrand(product.getBrandId()));
        long likeCount = likeService.countLikes(product.getId());
        return ProductInfo.of(product, brand, likeCount);
    }

    public PageInfo<ProductInfo> getProducts(Long brandId, String sort, int page, int size) {
        ProductSearchCondition condition = new ProductSearchCondition(brandId, ProductSortType.from(sort), new PageCondition(page, size));
        if (brandId != null) {
            brandService.getBrand(brandId);
        }

        return PageInfo.of(
            productInfoAssembler.assemble(productService.searchProducts(condition)),
            page,
            size,
            productService.countProducts(condition)
        );
    }
}
