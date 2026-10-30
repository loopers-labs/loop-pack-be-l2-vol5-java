package com.loopers.infrastructure.purchase;

import com.loopers.application.purchase.EventSource;
import com.loopers.application.purchase.port.OrderEventAnomalyRepository;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class OrderEventAnomalyPersistenceAdapter implements OrderEventAnomalyRepository {

    private static final int MAX_DETAIL_LENGTH = 1000;

    private final OrderEventAnomalyJpaRepository orderEventAnomalyJpaRepository;

    public OrderEventAnomalyPersistenceAdapter(OrderEventAnomalyJpaRepository orderEventAnomalyJpaRepository) {
        this.orderEventAnomalyJpaRepository = orderEventAnomalyJpaRepository;
    }

    @Override
    public void record(Kind kind, String eventId, Long orderId, EventSource source, String detail, Instant detectedAt) {
        // 소비는 partition마다 한 스레드라 같은 위치를 동시에 기록하지 않는다. 그래도 겹치면 유일 제약 위반이 일시적 실패로 재시도된다.
        if (orderEventAnomalyJpaRepository.existsByTopicAndPartitionNoAndOffsetNo(
            source.topic(), source.partition(), source.offset())) {
            return;
        }
        orderEventAnomalyJpaRepository.save(new OrderEventAnomalyJpaEntity(
            kind.name(), eventId, orderId, source.topic(), source.partition(), source.offset(), source.payload(),
            truncate(detail), detectedAt));
    }

    private String truncate(String detail) {
        if (detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH);
    }
}
