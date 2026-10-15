package com.loopers.application.product;

import com.loopers.application.product.port.ProductListCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductListCacheInvalidator {
    private final ProductListCache productListCache;

    public ProductListCacheInvalidator(ProductListCache productListCache) {
        this.productListCache = productListCache;
    }

    // commit 전에 버전을 올리면 그 사이 요청이 commit 전 값을 새 버전 키에 채워 TTL 동안 남는다.
    // commit 후에만 올리고, 롤백되면 이벤트가 버려져 올리지 않는다(설계 19절).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(ProductListChangedEvent event) {
        productListCache.invalidate();
    }
}
