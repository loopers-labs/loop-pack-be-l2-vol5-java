package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.RefundCase;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// 골격: w10s5 Green에서 저장·조회를 채운다.
@Repository
@Transactional
public class PaymentRefundCasePersistenceAdapter implements PaymentRefundCaseRepository {
    private final PaymentRefundCaseJpaRepository paymentRefundCaseJpaRepository;

    public PaymentRefundCasePersistenceAdapter(PaymentRefundCaseJpaRepository paymentRefundCaseJpaRepository) {
        this.paymentRefundCaseJpaRepository = paymentRefundCaseJpaRepository;
    }

    @Override
    public RefundCase save(RefundCase refundCase) {
        return refundCase;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefundCase> findByOrderId(long orderId) {
        return Optional.empty();
    }
}
