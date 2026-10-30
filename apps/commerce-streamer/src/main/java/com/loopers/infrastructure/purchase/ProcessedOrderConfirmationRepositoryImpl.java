package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProcessedOrderConfirmationRepositoryImpl implements ProcessedOrderConfirmationRepository {

    private final ProcessedOrderConfirmationJpaRepository processedOrderConfirmationJpaRepository;

    /** saveAndFlush — unique 판정을 commit까지 미루지 않고 이 호출에서 받는다. */
    @Override
    public ProcessedOrderConfirmation insert(ProcessedOrderConfirmation entity) {
        return processedOrderConfirmationJpaRepository.saveAndFlush(entity);
    }

    @Override
    public Optional<ProcessedOrderConfirmation> findByEventId(String eventId) {
        return processedOrderConfirmationJpaRepository.findByEventId(eventId);
    }

    @Override
    public Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId) {
        return processedOrderConfirmationJpaRepository.findByOrderId(orderId);
    }
}
