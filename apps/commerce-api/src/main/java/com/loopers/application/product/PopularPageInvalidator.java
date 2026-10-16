package com.loopers.application.product;

import com.loopers.domain.product.PopularPageCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "commerce.cache.popular.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PopularPageInvalidator {
    private final PopularPageCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(PopularPageChanged event) {
        try { cache.evict(event.brandId()); }
        catch (RuntimeException exception) {
            log.warn("Popular page invalidation failed: {}", exception.getClass().getSimpleName());
        }
    }
}
