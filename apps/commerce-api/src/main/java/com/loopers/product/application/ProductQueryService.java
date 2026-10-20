package com.loopers.product.application;

import com.loopers.brand.application.port.out.BrandPort;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.application.port.out.ProductQueryPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductSort;
import com.loopers.product.domain.ProductWithLikes;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 고객 상품 조회 유스케이스. 상품·좋아요 수 조회 결과에 브랜드 정보를 붙여 조합한다 (ADR-02).
 */
@RequiredArgsConstructor
@Component
public class ProductQueryService {

    private final ProductQueryPort productQueryPort;
    private final BrandPort brandPort;

    @Transactional(readOnly = true)
    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        Page<ProductWithLikes> products = productQueryPort.findSellable(brandId, sort, pageable);
        Map<Long, BrandModel> brandsById = brandsOf(products.getContent());
        return products.map(product -> ProductInfo.of(product, brandsById.get(product.product().getBrandId())));
    }

    @Transactional(readOnly = true)
    public ProductInfo getProduct(Long productId) {
        ProductWithLikes product = productQueryPort.findSellableById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
        return ProductInfo.of(product, brandsOf(List.of(product)).get(product.product().getBrandId()));
    }

    /**
     * W4 목록 캐시 hit: 캐시에 담긴 상품을 원본에서 다시 읽는다 (PK 조회 1번).
     * 하나라도 더는 팔 수 없으면 비어 있다 — 비노출은 신선도로 허용하지 않으므로 호출자가 원본 목록을 다시 읽는다.
     * 모두 팔 수 있으면 순서·브랜드·좋아요 수는 캐시 값, 이름·가격은 원본 값인 목록을 돌려준다.
     */
    @Transactional(readOnly = true)
    public Optional<List<ProductInfo>> refreshFromSource(List<ProductInfo> cached) {
        List<Long> ids = cached.stream().map(ProductInfo::id).toList();
        Map<Long, ProductModel> sellableById = productQueryPort.findSellableByIds(ids).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        if (!sellableById.keySet().containsAll(ids)) {
            return Optional.empty();
        }
        return Optional.of(cached.stream().map(info -> info.withSourceOf(sellableById.get(info.id()))).toList());
    }

    private Map<Long, BrandModel> brandsOf(List<ProductWithLikes> products) {
        List<Long> brandIds = products.stream().map(product -> product.product().getBrandId()).distinct().toList();
        return brandPort.findAllByIds(brandIds).stream()
            .collect(Collectors.toMap(BrandModel::getId, Function.identity()));
    }
}
