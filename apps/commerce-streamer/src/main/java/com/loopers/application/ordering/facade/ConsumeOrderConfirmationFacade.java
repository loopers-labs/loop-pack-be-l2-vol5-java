package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.service.RecordPurchaseActivityService;
import com.loopers.application.ordering.service.VerifyProcessedOrderConfirmationService;
import com.loopers.application.ordering.usecase.ConsumeOrderConfirmationUseCase;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.repository.PurchaseActivityRepository.InboxInsertConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsumeOrderConfirmationFacade implements ConsumeOrderConfirmationUseCase {
    private final RecordPurchaseActivityService recorder;
    private final VerifyProcessedOrderConfirmationService verifier;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public Result execute(OrderConfirmation confirmation) {
        try {
            return recorder.execute(confirmation);
        } catch (InboxInsertConflictException exception) {
            if (verifier.execute(confirmation)) {
                return Result.ALREADY_PROCESSED;
            }
            throw exception;
        }
    }
}
