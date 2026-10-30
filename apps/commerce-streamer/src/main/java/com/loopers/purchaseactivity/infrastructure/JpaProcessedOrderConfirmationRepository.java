package com.loopers.purchaseactivity.infrastructure;

import com.loopers.purchaseactivity.domain.DuplicateConfirmationException;
import com.loopers.purchaseactivity.domain.ProcessedOrderConfirmation;
import com.loopers.purchaseactivity.domain.ProcessedOrderConfirmationRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;

@Repository
public class JpaProcessedOrderConfirmationRepository implements ProcessedOrderConfirmationRepository {
    private final ProcessedOrderConfirmationJpaRepository repository;

    public JpaProcessedOrderConfirmationRepository(ProcessedOrderConfirmationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ProcessedOrderConfirmation> findByEventIdOrOrderId(String eventId, long orderId) {
        return repository.findByEventIdOrOrderId(eventId, orderId).stream()
                .map(ProcessedOrderConfirmationEntity::toDomain).toList();
    }

    @Override
    public void save(ProcessedOrderConfirmation confirmation) {
        try {
            repository.saveAndFlush(new ProcessedOrderConfirmationEntity(confirmation));
        } catch (DataIntegrityViolationException failure) {
            if (isDuplicateKey(failure)) {
                throw new DuplicateConfirmationException(failure);
            }
            throw failure;
        }
    }

    private boolean isDuplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }
}
