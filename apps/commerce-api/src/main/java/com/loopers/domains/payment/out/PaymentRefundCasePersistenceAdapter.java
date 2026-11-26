package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.RefundCase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PaymentRefundCasePersistenceAdapter implements PaymentRefundCaseRepository {
    private final PaymentRefundCaseJpaRepository paymentRefundCaseJpaRepository;

    public PaymentRefundCasePersistenceAdapter(PaymentRefundCaseJpaRepository paymentRefundCaseJpaRepository) {
        this.paymentRefundCaseJpaRepository = paymentRefundCaseJpaRepository;
    }

    @Override
    public RefundCase save(RefundCase refundCase) {
        // 호출자가 주문 행을 잠근 트랜잭션에 참여한다. 같은 주문의 대상 갱신도 그 잠금으로 직렬화된다.
        PaymentRefundCaseJpaEntity entity = paymentRefundCaseJpaRepository.findById(refundCase.getOrderId())
            .map(found -> {
                found.update(refundCase);
                return found;
            })
            .orElseGet(() -> new PaymentRefundCaseJpaEntity(refundCase));
        return paymentRefundCaseJpaRepository.save(entity).toRefundCase();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefundCase> findByOrderId(long orderId) {
        return paymentRefundCaseJpaRepository.findById(orderId).map(PaymentRefundCaseJpaEntity::toRefundCase);
    }

    @Override
    public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findDue(Instant now, int limit) {
        return List.of();
    }
}
