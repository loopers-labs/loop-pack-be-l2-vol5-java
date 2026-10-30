package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.OrderConfirmedContractViolation;
import com.loopers.domain.purchase.OrderConfirmedContractViolationRepository;
import com.loopers.domain.purchase.RecordAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedContractViolationRepositoryImpl implements OrderConfirmedContractViolationRepository {

    private final OrderConfirmedContractViolationJpaRepository orderConfirmedContractViolationJpaRepository;

    /** saveAndFlush — unique 판정을 commit까지 미루지 않고 이 호출에서 받는다. */
    @Override
    public OrderConfirmedContractViolation insert(OrderConfirmedContractViolation entity) {
        return orderConfirmedContractViolationJpaRepository.saveAndFlush(entity);
    }

    @Override
    public Optional<OrderConfirmedContractViolation> findByAddress(RecordAddress address) {
        return orderConfirmedContractViolationJpaRepository.findByRecordTopicAndRecordPartitionAndRecordOffset(address.topic(), address.partition(), address.offset());
    }
}
