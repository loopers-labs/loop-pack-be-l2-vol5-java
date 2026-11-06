package com.loopers.application.ordering.service;

import com.loopers.application.ordering.usecase.ConsumeOrderConfirmationUseCase.Result;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.repository.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class RecordPurchaseActivityService {
    private final PurchaseActivityRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result execute(OrderConfirmation confirmation, TransactionSynchronization completion) {
        TransactionSynchronizationManager.registerSynchronization(completion);
        if (VerifyProcessedOrderConfirmationService.matchesCommittedFact(repository, confirmation)) {
            return Result.ALREADY_PROCESSED;
        }
        long inboxId = repository.insertInbox(confirmation);
        repository.insertActivities(inboxId, confirmation);
        return Result.CREATED;
    }
}
