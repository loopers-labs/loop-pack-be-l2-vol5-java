package com.loopers.infrastructure.persistence.ordering;

import com.loopers.domain.ordering.repository.OrderEventFailureRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Arrays;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class OrderEventFailureJpaRepository implements OrderEventFailureRepository {
    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertOrVerify(Failure failure) {
        bindPosition(entityManager.createNativeQuery("""
            INSERT INTO order_event_failures
                (consumer_group, source_topic, source_partition, source_offset, message_key, payload, event_id, reason, recorded_at)
            VALUES (:groupId, :topic, :partitionId, :offsetId, :messageKey, :payload, :eventId, :reason, UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """), failure)
            .setParameter("messageKey", failure.key())
            .setParameter("payload", failure.value())
            .setParameter("eventId", failure.eventId() == null ? null : failure.eventId().toString())
            .setParameter("reason", failure.reason())
            .executeUpdate();
        Object[] stored = (Object[]) bindPosition(entityManager.createNativeQuery("""
            SELECT message_key, payload FROM order_event_failures
            WHERE consumer_group = :groupId AND source_topic = :topic
              AND source_partition = :partitionId AND source_offset = :offsetId
            FOR UPDATE
            """).unwrap(NativeQuery.class)
            .addScalar("message_key", String.class).addScalar("payload", byte[].class), failure).getSingleResult();
        if (!Objects.equals(stored[0], failure.key()) || !Arrays.equals((byte[]) stored[1], failure.value())) {
            throw new IllegalStateException("같은 Kafka 위치의 기존 실패 기록과 수신 원문이 다릅니다.");
        }
    }

    private Query bindPosition(Query query, Failure failure) {
        return query.setParameter("groupId", failure.group())
            .setParameter("topic", failure.topic())
            .setParameter("partitionId", failure.partition())
            .setParameter("offsetId", failure.offset());
    }
}
