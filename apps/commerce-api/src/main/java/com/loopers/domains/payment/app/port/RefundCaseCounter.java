package com.loopers.domains.payment.app.port;

import java.util.Map;

public interface RefundCaseCounter {
    Map<String, Long> countByStatus();
}
