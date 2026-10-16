package com.loopers.support.cache;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hits read authoritative revisions before Redis; misses load inside a DB snapshot. */
@Component
public class CatalogReadTransaction {
    private final TransactionTemplate transaction;
    private final ResponseCache cache;
    private final CacheSingleFlight flights;
    public CatalogReadTransaction(PlatformTransactionManager manager, ResponseCache cache, CacheSingleFlight flights) {
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.cache = cache;
        this.flights = flights;
    }
    public <T> T read(Supplier<T> operation) {
        while (true) {
            try { return cache.hitOnly(operation); }
            catch (ResponseCache.CacheMiss miss) {
                if (miss.key == null) return transaction.execute(status -> operation.get());
                // Acquire before opening a DB transaction. Only the owner starts a snapshot.
                var result = flights.execute(miss.key, () -> transaction.execute(status -> operation.get()));
                if (result.owner()) return result.value();
                // Never share the owner's composed response: repeat all cache reads.
            }
        }
    }
}
