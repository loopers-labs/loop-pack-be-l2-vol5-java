package com.loopers.application.product;

import com.loopers.domain.product.CachedFirstPage;
import com.loopers.domain.product.PricePageCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "commerce.cache.price.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PricePageInvalidator {
    private final PricePageCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(PricePageChanged event) {
        try {
            cache.evict(event.brandId(), CachedFirstPage.PAGE, CachedFirstPage.SIZE);
        } catch (RuntimeException exception) {
            // Redis failure must not turn an already committed DB change into a reported failure.
            log.warn("Price page invalidation failed: {}", exception.getClass().getSimpleName());
        }
    }
}
