package com.loopers.application.product;

import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    /** 캐시하는 크기. 외부 계약의 기본 크기다 (4주차 docs/week4/cache-design.md 2.1) */
    static final int CACHEABLE_SIZE = 20;

    private final ProductService productService;
    private final ProductListCache productListCache;

    /**
     * 고객 쪽 brandId 는 조회 조건이다. 없는 브랜드면 빈 목록이 된다 (설계 D-24).
     * 첫 페이지 · 기본 크기만 캐시를 거친다 (4주차 2.1). 이 메서드는 트랜잭션을 열지 않아,
     * 캐시가 범위 버전을 읽은 뒤에 원본 읽기 트랜잭션이 시작된다 (4주차 4.1)
     */
    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        Page<ProductView> views = isCacheable(pageable)
            ? productListCache.getFirstPage(brandId, sort, () -> productService.getActiveProductViews(brandId, sort, pageable))
            : productService.getActiveProductViews(brandId, sort, pageable);
        return views.map(ProductInfo::from);
    }

    public ProductInfo getProduct(Long productId) {
        return ProductInfo.from(productService.getActiveProductView(productId));
    }

    private static boolean isCacheable(Pageable pageable) {
        return pageable.getPageNumber() == 0 && pageable.getPageSize() == CACHEABLE_SIZE;
    }
}
