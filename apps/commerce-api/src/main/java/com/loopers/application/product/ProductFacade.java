package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductWithLikes;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 고객 상품 조회 유스케이스. 상품·좋아요 수 조회 결과에 브랜드 정보를 붙여 조합한다 (ADR-02).
 */
@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final ProductQueryRepository productQueryRepository;
    private final BrandRepository brandRepository;

    @Transactional(readOnly = true)
    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        Page<ProductWithLikes> products = productQueryRepository.findSellable(brandId, sort, pageable);
        Map<Long, BrandModel> brandsById = brandsOf(products.getContent());
        return products.map(product -> ProductInfo.of(product, brandsById.get(product.product().getBrandId())));
    }

    @Transactional(readOnly = true)
    public ProductInfo getProduct(Long productId) {
        ProductWithLikes product = productQueryRepository.findSellableById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
        return ProductInfo.of(product, brandsOf(List.of(product)).get(product.product().getBrandId()));
    }

    private Map<Long, BrandModel> brandsOf(List<ProductWithLikes> products) {
        List<Long> brandIds = products.stream().map(product -> product.product().getBrandId()).distinct().toList();
        return brandRepository.findAllByIds(brandIds).stream()
            .collect(Collectors.toMap(BrandModel::getId, Function.identity()));
    }
}
