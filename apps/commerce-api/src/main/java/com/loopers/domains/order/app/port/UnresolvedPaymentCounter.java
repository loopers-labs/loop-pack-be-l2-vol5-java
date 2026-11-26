package com.loopers.domains.order.app.port;

import java.util.Map;

public interface UnresolvedPaymentCounter {
    // 주문이 PAYMENT_PENDING인 PG 결제 기록 수를 provider_status 이름별로 돌려준다. 행이 없는 상태는 키가 없다.
    Map<String, Long> countByProviderStatus();
}
