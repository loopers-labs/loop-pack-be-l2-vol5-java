package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.OrderConfirmedContractViolation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderConfirmedContractViolationJpaRepository extends JpaRepository<OrderConfirmedContractViolation, Long> {

    Optional<OrderConfirmedContractViolation> findByRecordTopicAndRecordPartitionAndRecordOffset(
        String recordTopic, int recordPartition, long recordOffset);
}
