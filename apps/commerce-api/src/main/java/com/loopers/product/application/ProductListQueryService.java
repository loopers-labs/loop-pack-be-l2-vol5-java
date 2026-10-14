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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
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
        if (insideWriteTransaction()) {
            // 쓰기 트랜잭션 안에서 읽은 값은 아직 commit 전일 수 있다. rollback되면 존재하지 않는 결과가 되므로 캐시를 거치지 않는다.
            return productQueryService.getProducts(brandId, sort, pageable);
        }
        ProductListKey key = ProductListKey.of(brandId, sort, pageable);
        // 세대는 DB를 읽기 전에 읽는다. 그 사이 변경이 commit되면 세대가 올라가, 아래에서 저장하는 결과는 옛 세대에 들어간다.
        long generation = productListCachePort.currentGeneration();
        Optional<ProductListPage> cached = productListCachePort.find(generation, key);
        // 가격·이름·좋아요 수는 무효화가 실패하면 TTL 동안 옛 값일 수 있다(표시 신선도). 그러나 삭제된 상품의 비노출은
        // 신선도로 허용하지 않으므로, hit여도 담긴 상품이 아직 팔 수 있는지 원본에서 확인한다. 하나라도 아니면 miss로 다룬다.
        if (cached.isPresent() && productQueryService.areAllSellable(idsOf(cached.get()))) {
            return cached.get().toPage();
        }
        Page<ProductInfo> page = productQueryService.getProducts(brandId, sort, pageable);
        productListCachePort.save(generation, key, ProductListPage.from(page));
        return page;
    }

    private static List<Long> idsOf(ProductListPage page) {
        return page.content().stream().map(ProductInfo::id).toList();
    }

    private static boolean insideWriteTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
}
