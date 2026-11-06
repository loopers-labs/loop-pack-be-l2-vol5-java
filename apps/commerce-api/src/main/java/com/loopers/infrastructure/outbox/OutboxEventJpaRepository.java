package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByEventId(String eventId);

    List<OutboxEvent> findAllByOrderIdOrderByIdAsc(Long orderId);

    // idx(status, next_attempt_at, id)를 타는 경로다(D-15).
    @Query("select e.id from OutboxEvent e where e.status = :status and e.nextAttemptAt <= :now order by e.id asc")
    List<Long> findDueIds(@Param("status") OutboxStatus status, @Param("now") ZonedDateTime now, Limit limit);

    // 집기와 임대를 한 문장으로 끝낸다(D-4). 영속성 컨텍스트를 비워, 뒤이은 조회가 바뀌기 전의 엔티티를 돌려주지 않게 한다.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutboxEvent e set e.attemptCount = e.attemptCount + 1, e.nextAttemptAt = :leaseUntil "
        + "where e.id = :id and e.status = :status and e.nextAttemptAt <= :now")
    int claim(@Param("id") Long id, @Param("status") OutboxStatus status, @Param("now") ZonedDateTime now,
              @Param("leaseUntil") ZonedDateTime leaseUntil);

    long countByStatus(OutboxStatus status);

    @Query("select min(e.createdAt) from OutboxEvent e where e.status = :status")
    ZonedDateTime findOldestCreatedAt(@Param("status") OutboxStatus status);
}
