package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

@Repository
public class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {
    private final ProcessedOrderConfirmationJpaRepository processedRepository;
    private final ProductPurchaseActivityJpaRepository activityRepository;

    public PurchaseActivityRepositoryImpl(ProcessedOrderConfirmationJpaRepository processedRepository,
                                           ProductPurchaseActivityJpaRepository activityRepository) {
        this.processedRepository = processedRepository;
        this.activityRepository = activityRepository;
    }

    @Override
    public List<ProcessedOrderConfirmation> findProcessed(String eventId, long orderId) {
        return processedRepository.findAllByEventIdOrOrderId(eventId, orderId).stream()
            .map(ProcessedOrderConfirmationJpaEntity::toDomain).toList();
    }

    @Override
    public List<ProductPurchaseActivity> findActivities(long orderId) {
        return activityRepository.findAllByOrderIdOrderByProductIdAsc(orderId).stream()
            .map(ProductPurchaseActivityJpaEntity::toDomain).toList();
    }

    @Override
    public void saveCompletion(ProcessedOrderConfirmation confirmation) {
        try {
            processedRepository.saveAndFlush(ProcessedOrderConfirmationJpaEntity.create(confirmation));
        } catch (DataIntegrityViolationException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                    && isCompletionConstraint(violation.getConstraintName())) {
                    throw new DuplicateOrderConfirmationException(e);
                }
            }
            throw e;
        }
    }

    @Override
    public void saveActivity(ProductPurchaseActivity activity) {
        activityRepository.saveAndFlush(ProductPurchaseActivityJpaEntity.create(activity));
    }

    private static boolean isCompletionConstraint(String constraintName) {
        if (constraintName == null) {
            return false;
        }
        String name = constraintName.substring(constraintName.lastIndexOf('.') + 1);
        return ProcessedOrderConfirmationJpaEntity.EVENT_UNIQUE_CONSTRAINT.equalsIgnoreCase(name)
            || ProcessedOrderConfirmationJpaEntity.ORDER_UNIQUE_CONSTRAINT.equalsIgnoreCase(name);
    }
}
