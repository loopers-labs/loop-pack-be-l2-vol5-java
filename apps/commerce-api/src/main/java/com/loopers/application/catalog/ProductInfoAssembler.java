package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.BrandService;
import com.loopers.domain.catalog.ProductLikeService;
import com.loopers.domain.catalog.ProductModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 상품 + 브랜드 정보 + 좋아요 수 조합 (같은 BC 안, FR-PRODUCT-01/02, FR-ADMIN-PRODUCT-01/03, FR-LIKE-03). */
@RequiredArgsConstructor
@Component
public class ProductInfoAssembler {
    private final BrandService brandService;
    private final ProductLikeService productLikeService;

    public ProductInfo assemble(ProductModel product) {
        return assemble(List.of(product)).get(0);
    }

    public List<ProductInfo> assemble(List<ProductModel> products) {
        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        List<Long> brandIds = products.stream().map(ProductModel::getBrandId).distinct().toList();
        Map<Long, BrandModel> brands = brandService.getByIds(brandIds);
        Map<Long, Long> likeCounts = productLikeService.countOf(productIds);
        return products.stream()
            .map(product -> ProductInfo.of(
                product,
                brands.get(product.getBrandId()),
                likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }
}
