package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.PaymentAnomalyRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PaymentAnomalyPersistenceAdapter implements PaymentAnomalyRepository {
    private final PaymentAnomalyJpaRepository paymentAnomalyJpaRepository;

    public PaymentAnomalyPersistenceAdapter(PaymentAnomalyJpaRepository paymentAnomalyJpaRepository) {
        this.paymentAnomalyJpaRepository = paymentAnomalyJpaRepository;
    }

    @Override
    public void record(long orderId, Kind kind, String summary, Instant now) {
        // 호출자가 주문 행 잠금을 쥔다고 가정하지 않는다. APPLY_FAILED는 반영 트랜잭션이 rollback된 뒤 잠금 없이 기록되므로
        // 같은 주문의 기록이 겹칠 수 있다. 고유 키 위의 upsert 한 문장으로 기록해 잠금 없이도 원자적이다(w10s7-P3).
        paymentAnomalyJpaRepository.upsert(orderId, kind.name(), summary, now);
    }

    @Override
    public void resolveTransient(long orderId, Instant now) {
        paymentAnomalyJpaRepository.findByOrderIdAndKindInAndResolvedAtIsNull(orderId,
            List.of(Kind.LOOKUP_FAILED.name(), Kind.NO_TRANSACTION.name())).forEach(anomaly -> anomaly.resolve(now));
    }
}
