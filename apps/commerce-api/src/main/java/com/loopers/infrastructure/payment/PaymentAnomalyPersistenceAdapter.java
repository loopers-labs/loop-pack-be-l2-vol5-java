package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentAnomalyRepository;
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
        // 호출자가 주문 행을 잠근 트랜잭션에 참여한다. 같은 주문의 이상 갱신도 그 잠금으로 직렬화된다.
        PaymentAnomalyJpaEntity anomaly = paymentAnomalyJpaRepository.findByOrderIdAndKind(orderId, kind.name())
            .orElseGet(() -> new PaymentAnomalyJpaEntity(orderId, kind.name(), now));
        anomaly.observe(summary, now);
        paymentAnomalyJpaRepository.save(anomaly);
    }

    @Override
    public void resolveTransient(long orderId, Instant now) {
        paymentAnomalyJpaRepository.findByOrderIdAndKindInAndResolvedAtIsNull(orderId,
            List.of(Kind.LOOKUP_FAILED.name(), Kind.NO_TRANSACTION.name())).forEach(anomaly -> anomaly.resolve(now));
    }
}
