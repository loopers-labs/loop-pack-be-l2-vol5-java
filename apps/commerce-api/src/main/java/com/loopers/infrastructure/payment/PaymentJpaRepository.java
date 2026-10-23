package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentModel, Long> {

    Optional<PaymentModel> findByOrderId(Long orderId);

    boolean existsByAttemptKey(String attemptKey);

    /** idx_payment_provider_order 를 쓰는 스칼라 조회. DISTINCT 로 중복을 숨기지 않는다. */
    @Query("""
        select p.orderId from PaymentModel p
        where p.provider = :provider and p.providerAccount = :account and p.providerOrderId = :providerOrderId
        """)
    List<Long> findOrderIdsByProviderOrderId(@Param("provider") String provider, @Param("account") String account,
                                             @Param("providerOrderId") String providerOrderId);

    List<PaymentModel> findAllByOrderIdIn(Collection<Long> orderIds);
}
