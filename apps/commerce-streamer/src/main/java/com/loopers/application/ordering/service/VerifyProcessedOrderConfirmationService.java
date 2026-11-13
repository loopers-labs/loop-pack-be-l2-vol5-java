package com.loopers.application.ordering.service;

import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.model.OrderConfirmationConflictException;
import com.loopers.domain.ordering.repository.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VerifyProcessedOrderConfirmationService {
    private final PurchaseActivityRepository repository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean execute(OrderConfirmation confirmation) {
        return matchesCommittedFact(repository, confirmation);
    }

    static boolean matchesCommittedFact(PurchaseActivityRepository repository, OrderConfirmation confirmation) {
        var candidates = repository.findCandidates(confirmation.eventId(), confirmation.orderId());
        if (candidates.isEmpty()) {
            return false;
        }
        if (candidates.size() == 1 && candidates.getFirst().equals(confirmation)) {
            return true;
        }
        throw new OrderConfirmationConflictException(confirmation);
    }
}
