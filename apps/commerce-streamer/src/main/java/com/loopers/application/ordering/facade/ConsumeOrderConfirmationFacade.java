package com.loopers.application.ordering.facade;

import com.loopers.application.ordering.service.RecordPurchaseActivityService;
import com.loopers.application.ordering.service.VerifyProcessedOrderConfirmationService;
import com.loopers.application.ordering.usecase.ConsumeOrderConfirmationUseCase;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.repository.PurchaseActivityRepository.InboxInsertConflictException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;

@Service
@RequiredArgsConstructor
public class ConsumeOrderConfirmationFacade implements ConsumeOrderConfirmationUseCase {
    private final RecordPurchaseActivityService recorder;
    private final VerifyProcessedOrderConfirmationService verifier;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public Result execute(OrderConfirmation confirmation) {
        var rolledBack = new AtomicBoolean();
        try {
            return recorder.execute(confirmation, new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    rolledBack.set(status == STATUS_ROLLED_BACK);
                }
            });
        } catch (RuntimeException exception) {
            if (!rolledBack.get() && !(exception instanceof CannotCreateTransactionException)) {
                throw new TransactionSystemException("구매 활동 트랜잭션의 rollback을 확인하지 못했습니다.", exception);
            }
            if (exception instanceof InboxInsertConflictException && verifier.execute(confirmation)) {
                return Result.ALREADY_PROCESSED;
            }
            throw exception;
        }
    }
}
