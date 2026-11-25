package com.loopers.domains.order.app;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class UnresolvedPaymentQueryService {
    public List<UnresolvedPaymentView> list(Set<String> providerStatuses, long minAgeSeconds, int page, int size) {
        return List.of();
    }
}
