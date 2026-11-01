package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProcessedOrderConfirmationRepositoryImpl implements ProcessedOrderConfirmationRepository {
    private final ProcessedOrderConfirmationJpaRepository jpaRepository;

    @Override
    public ProcessedOrderConfirmation saveAndFlush(ProcessedOrderConfirmation confirmation) {
        return jpaRepository.saveAndFlush(confirmation);
    }

    @Override
    public List<ProcessedOrderConfirmation> findByEventIdOrOrderId(String eventId, Long orderId) {
        return jpaRepository.findByEventIdOrOrderId(eventId, orderId);
    }
}
