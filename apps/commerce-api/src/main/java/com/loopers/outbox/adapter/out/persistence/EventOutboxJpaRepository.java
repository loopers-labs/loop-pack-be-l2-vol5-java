package com.loopers.outbox.adapter.out.persistence;

import com.loopers.outbox.domain.EventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결과 표시는 "WHERE published_at IS NULL" 조건부 UPDATE다 — 늦게 온 실패가 성공을 덮지 않고, 엔티티 전체를 다시 쓰지 않는다.
 */
public interface EventOutboxJpaRepository extends JpaRepository<EventOutbox, Long> {

    Optional<EventOutbox> findByEventId(String eventId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EventOutbox o SET o.publishedAt = :at, o.publishedPartition = :partition, o.publishedOffset = :offset, o.updatedAt = :at "
        + "WHERE o.eventId = :eventId AND o.publishedAt IS NULL")
    int markPublished(
        @Param("eventId") String eventId,
        @Param("partition") int partition,
        @Param("offset") long offset,
        @Param("at") ZonedDateTime at
    );

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EventOutbox o SET o.lastError = :error, o.updatedAt = :at WHERE o.eventId = :eventId AND o.publishedAt IS NULL")
    int markFailed(@Param("eventId") String eventId, @Param("error") String error, @Param("at") ZonedDateTime at);

    @Query("SELECT o FROM EventOutbox o WHERE o.publishedAt IS NULL AND o.createdAt < :before ORDER BY o.createdAt, o.id")
    List<EventOutbox> findUnpublishedCreatedBefore(@Param("before") ZonedDateTime before);
}
