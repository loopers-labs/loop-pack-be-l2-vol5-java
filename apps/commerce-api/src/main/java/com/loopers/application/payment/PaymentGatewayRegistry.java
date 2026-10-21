package com.loopers.application.payment;

import com.loopers.application.payment.port.PaymentGateway;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 어댑터와 우선순위 설정이 정확히 일치하지 않으면 시작을 거절한다. 잘못된 설정이 결제 도중에 드러나지 않게 하기 위해서다.
public class PaymentGatewayRegistry {
    private final Map<String, PaymentGateway> gateways;
    private final List<PaymentGateway> priorityOrder;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways, List<String> priority) {
        if (gateways.isEmpty() || priority.isEmpty()) {
            throw new IllegalStateException("PG 어댑터와 payment.providers.priority 설정이 각각 하나 이상 필요합니다.");
        }
        Map<String, PaymentGateway> byId = new LinkedHashMap<>();
        for (PaymentGateway gateway : gateways) {
            if (byId.putIfAbsent(gateway.providerId(), gateway) != null) {
                throw new IllegalStateException("PG providerId가 중복되었습니다: " + gateway.providerId());
            }
        }
        Set<String> prioritized = new HashSet<>();
        for (String providerId : priority) {
            if (!prioritized.add(providerId)) {
                throw new IllegalStateException("PG 우선순위에 같은 providerId가 반복되었습니다: " + providerId);
            }
            if (!byId.containsKey(providerId)) {
                throw new IllegalStateException("PG 우선순위의 providerId에 해당하는 어댑터가 없습니다: " + providerId);
            }
        }
        for (String providerId : byId.keySet()) {
            if (!prioritized.contains(providerId)) {
                throw new IllegalStateException("PG 어댑터의 providerId가 우선순위 설정에 없습니다: " + providerId);
            }
        }
        this.gateways = Map.copyOf(byId);
        this.priorityOrder = priority.stream().map(byId::get).toList();
    }

    public PaymentGateway get(String providerId) {
        PaymentGateway gateway = gateways.get(providerId);
        if (gateway == null) {
            throw new IllegalArgumentException("등록되지 않은 PG providerId입니다: " + providerId);
        }
        return gateway;
    }

    public List<PaymentGateway> inPriorityOrder() {
        return priorityOrder;
    }
}
