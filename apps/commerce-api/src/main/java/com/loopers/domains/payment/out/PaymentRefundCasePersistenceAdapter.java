package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.RefundCase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
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
        // 호출자가 대상 행을 잠근(findByOrderIdForUpdate) 트랜잭션에 참여한다. 합류와 환불 추적의 갱신이 그 잠금으로 직렬화된다.
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

    // 엔티티를 영속성 컨텍스트에 올리지 않는다. 올리면 뒤이은 FOR UPDATE 조회가 DB의 최신 행 대신 먼저 읽은 상태를 돌려준다.
    @Override
    @Transactional(readOnly = true)
    public boolean existsByOrderId(long orderId) {
        return paymentRefundCaseJpaRepository.existsById(orderId);
    }

    @Override
    public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
        return paymentRefundCaseJpaRepository.findForUpdate(orderId).map(PaymentRefundCaseJpaEntity::toRefundCase);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findDue(Instant now, int limit) {
        return paymentRefundCaseJpaRepository.findDue(now, PageRequest.of(0, limit));
    }

    @Override
    public void recordCheck(long orderId, RefundCase.CheckOutcome outcome, String cause, Instant checkedAt) {
    }
}
