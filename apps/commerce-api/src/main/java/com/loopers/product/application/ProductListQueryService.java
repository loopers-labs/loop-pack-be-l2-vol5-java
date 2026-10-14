package com.loopers.product.application;

import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.application.port.out.ProductListCachePort;
import com.loopers.product.application.port.out.ProductListKey;
import com.loopers.product.application.port.out.ProductListPage;
import com.loopers.product.domain.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 고객 상품 목록의 cache-aside (W4). 캐시를 먼저 보고, 없으면 DB에서 읽어 캐시에 넣는다.
 */
@RequiredArgsConstructor
@Component
public class ProductListQueryService {

    private final ProductQueryService productQueryService;
    private final ProductListCachePort productListCachePort;

    public Page<ProductInfo> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        ProductListKey key = ProductListKey.of(brandId, sort, pageable);
        Optional<ProductListPage> cached = productListCachePort.find(key);
        if (cached.isPresent()) {
            return cached.get().toPage();
        }
        Page<ProductInfo> page = productQueryService.getProducts(brandId, sort, pageable);
        productListCachePort.save(key, ProductListPage.from(page));
        return page;
    }
}
