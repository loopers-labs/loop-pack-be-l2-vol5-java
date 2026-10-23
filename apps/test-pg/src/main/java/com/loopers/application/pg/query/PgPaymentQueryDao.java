package com.loopers.application.pg.query;

import java.util.List;
import java.util.Optional;

public interface PgPaymentQueryDao {
    Optional<PgLookupView> findByKey(String transactionKey);
    List<PgLookupView> findByOrderId(String orderId);
}
