package com.loopers.application.product;

import com.loopers.domain.product.CachedFirstPage;
import com.loopers.domain.product.PricePage;
import com.loopers.domain.product.PricePageCache;
import com.loopers.domain.product.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PricePageReader {
    private final ProductService products;
    private final PricePageCache cache;
    private final boolean enabled;
    public PricePageReader(ProductService products, PricePageCache cache,
        @Value("${commerce.cache.price.enabled:false}") boolean enabled) {
        this.products = products;
        this.cache = cache;
        this.enabled = enabled;
    }
    @Transactional(readOnly = true)
    public PricePage get(Long brandId, int page, int size) {
        // First-page experiment only. A write transaction must never publish its uncommitted data.
        boolean eligible = enabled && brandId != null && CachedFirstPage.matches(page, size)
            && TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (eligible) {
            var hit = cache.get(brandId, page, size);
            if (hit.isPresent()) {
                var ids = hit.get().items().stream().map(PricePage.Item::id).toList();
                // A failed DEL or late fill must never expose a deleted product.
                if (products.findActiveIds(brandId, ids).size() == ids.size()) { return hit.get(); }
            }
        }
        var items = products.findActiveByPriceAsc(brandId, page, size).stream()
            .map(p -> new PricePage.Item(p.getId(), p.getName(), p.getPrice(), p.getCreatedAt())).toList();
        var result = new PricePage(items);
        if (eligible) { cache.put(brandId, page, size, result); }
        return result;
    }
}
