package com.loopers.infrastructure.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAnomalyJpaRepository extends JpaRepository<PaymentAnomalyJpaEntity, Long> {
    Optional<PaymentAnomalyJpaEntity> findByOrderIdAndKind(long orderId, String kind);

    List<PaymentAnomalyJpaEntity> findByOrderIdAndKindInAndResolvedAtIsNull(long orderId, Collection<String> kinds);
}
