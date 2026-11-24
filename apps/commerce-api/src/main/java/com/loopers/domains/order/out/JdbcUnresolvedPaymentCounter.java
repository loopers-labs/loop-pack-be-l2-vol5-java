package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.UnresolvedPaymentCounter;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUnresolvedPaymentCounter implements UnresolvedPaymentCounter {
    @Override
    public Map<String, Long> countByProviderStatus() {
        return Map.of();
    }
}
