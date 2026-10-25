package com.loopers.testpg.domain;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findByTransactionKey(String transactionKey);
    List<Payment> findAllByOrderId(String orderId);
    // PENDING 거래만 지정한 종결 결과로 한 번 바꾸고, 바뀐 거래를 반환한다.
    Optional<Payment> completeIfPending(String transactionKey, PaymentStatus result);
}
