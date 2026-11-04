package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProcessedOrderConfirmationRepositoryImpl implements ProcessedOrderConfirmationRepository {
    private final ProcessedOrderConfirmationJpaRepository confirmations;

    @Override
    public Optional<ProcessedOrderConfirmation> findByEventId(String eventId) {
        return confirmations.findByEventId(eventId);
    }

    @Override
    public Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId) {
        return confirmations.findByOrderId(orderId);
    }

    @Override
    public void save(ProcessedOrderConfirmation confirmation) {
        try {
            confirmations.saveAndFlush(confirmation);
        } catch (DataIntegrityViolationException exception) {
            if (isIdentityConflict(exception)) {
                throw new DuplicateOrderConfirmationException(exception);
            }
            throw exception;
        }
    }

    private boolean isIdentityConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                && violation.getSQLException().getErrorCode() == 1062 && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "").replace("'", "").replace("\"", "");
                name = name.substring(name.lastIndexOf('.') + 1);
                return name.equals(ProcessedOrderConfirmation.EVENT_CONSTRAINT)
                    || name.equals(ProcessedOrderConfirmation.ORDER_CONSTRAINT);
            }
        }
        return false;
    }
}
