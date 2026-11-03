package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ConfirmationAlreadyRecordedException;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityStore;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Repository
public class JpaPurchaseActivityStore implements PurchaseActivityStore {
    private static final Set<String> COMPLETION_IDENTITIES = Set.of(
        "uk_processed_order_confirmation_event_id", "uk_processed_order_confirmation_order_id");
    private final ProcessedOrderConfirmationJpaRepository confirmations;
    private final ProductPurchaseActivityJpaRepository activities;

    public JpaPurchaseActivityStore(ProcessedOrderConfirmationJpaRepository confirmations,
                                     ProductPurchaseActivityJpaRepository activities) {
        this.confirmations = confirmations;
        this.activities = activities;
    }

    @Override
    public void insertConfirmation(OrderConfirmation confirmation) {
        try {
            confirmations.saveAndFlush(new ProcessedOrderConfirmation(confirmation));
        } catch (DataIntegrityViolationException failure) {
            if (completionIdentityCollision(failure)) {
                throw new ConfirmationAlreadyRecordedException(failure);
            }
            throw failure;
        }
    }

    @Override
    public void insertActivity(long orderId, Instant confirmedAt, OrderConfirmation.Item item) {
        activities.saveAndFlush(new ProductPurchaseActivity(orderId, confirmedAt, item));
    }

    @Override
    public Optional<OrderConfirmation> findByEventId(String eventId) {
        return confirmations.findByEventId(eventId).map(this::snapshot);
    }

    @Override
    public Optional<OrderConfirmation> findByOrderId(long orderId) {
        return confirmations.findByOrderId(orderId).map(this::snapshot);
    }

    private OrderConfirmation snapshot(ProcessedOrderConfirmation confirmation) {
        return confirmation.snapshot(activities.findByOrderIdOrderByProductIdAsc(confirmation.orderId()));
    }

    private boolean completionIdentityCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getErrorCode() == 1062) {
                String name = violation.getConstraintName();
                if (name != null) {
                    String unquoted = name.replace("`", "").replace("'", "");
                    String unqualified = unquoted.substring(unquoted.lastIndexOf('.') + 1);
                    if (COMPLETION_IDENTITIES.contains(unqualified)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
