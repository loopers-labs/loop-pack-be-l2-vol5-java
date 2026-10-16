package com.loopers.config.cache;

import com.loopers.product.domain.Stock;
import java.util.IdentityHashMap;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Remember the locked row's display state without another SQL query or domain cache fields. */
final class StockCacheState implements TransactionSynchronization {
    private final IdentityHashMap<Stock, Boolean> soldOut = new IdentityHashMap<>();

    static void observe(Stock stock) {
        StockCacheState state = current();
        if (state != null) state.soldOut.putIfAbsent(stock, stock.quantity() == 0);
    }

    static boolean changed(Stock stock) {
        StockCacheState state = current();
        if (state == null) return true;
        Boolean previous = state.soldOut.put(stock, stock.quantity() == 0);
        // Creation or a caller that did not read a locked row: invalidate conservatively.
        return previous == null || previous != (stock.quantity() == 0);
    }

    private static StockCacheState current() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) return null;
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof StockCacheState state) return state;
        }
        var state = new StockCacheState();
        // Synchronizations belong to the current transaction, including REQUIRES_NEW suspension.
        TransactionSynchronizationManager.registerSynchronization(state);
        return state;
    }
}
