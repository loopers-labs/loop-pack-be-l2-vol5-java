package com.loopers.domains.order.app;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PaymentReconcileBatchService {
    public ReconcileBatchResult reconcile(List<Long> orderIds) {
        return new ReconcileBatchResult(0, 0, null, List.of());
    }
}
