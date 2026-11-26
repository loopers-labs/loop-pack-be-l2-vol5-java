package com.loopers.domains.payment.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAnomalyJpaRepository extends JpaRepository<PaymentAnomalyJpaEntity, Long> {
    List<PaymentAnomalyJpaEntity> findByOrderIdAndKindInAndResolvedAtIsNull(long orderId, Collection<String> kinds);

    // 주문·kind 고유 키 위에서 첫 기록과 재발을 한 문장으로 처리한다. 읽고 쓰는 사이가 없어 동시 기록이 겹쳐도
    // 키 충돌로 실패하거나 occurrences가 유실되지 않는다. 같은 트랜잭션의 대기 중인 변경을 먼저 DB로 보낸다.
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into payment_anomalies"
        + " (order_id, kind, first_seen_at, last_seen_at, occurrences, last_summary, resolved_at)"
        + " values (:orderId, :kind, :now, :now, 1, :summary, null) as incoming"
        + " on duplicate key update occurrences = payment_anomalies.occurrences + 1,"
        + " last_seen_at = incoming.last_seen_at, last_summary = incoming.last_summary, resolved_at = null",
        nativeQuery = true)
    int upsert(@Param("orderId") long orderId, @Param("kind") String kind, @Param("summary") String summary,
        @Param("now") Instant now);
}
