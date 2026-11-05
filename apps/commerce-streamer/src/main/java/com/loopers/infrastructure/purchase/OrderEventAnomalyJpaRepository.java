package com.loopers.infrastructure.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventAnomalyJpaRepository extends JpaRepository<OrderEventAnomalyJpaEntity, Long> {

    boolean existsByTopicAndPartitionNoAndOffsetNo(String topic, int partitionNo, long offsetNo);
}
