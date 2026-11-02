package com.loopers.infrastructure.purchase.persistence;

import com.loopers.domain.purchase.entity.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.repository.ProcessedOrderConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProcessedOrderConfirmationRepositoryImpl implements ProcessedOrderConfirmationRepository {
    private final ProcessedOrderConfirmationJpaRepository processedOrderConfirmationJpaRepository;

    /** IDENTITY 라 save 에서 바로 INSERT 됨. 유일 제약 위반도 commit 전 이 자리에서 드러남 */
    @Override
    public ProcessedOrderConfirmation save(ProcessedOrderConfirmation processed) {
        return processedOrderConfirmationJpaRepository.save(processed);
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
