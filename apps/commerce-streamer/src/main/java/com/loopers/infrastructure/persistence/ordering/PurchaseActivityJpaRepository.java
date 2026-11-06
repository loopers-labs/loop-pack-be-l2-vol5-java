package com.loopers.infrastructure.persistence.ordering;

import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.repository.PurchaseActivityRepository;
import com.loopers.infrastructure.persistence.ordering.entity.OrderConfirmationInboxJpaEntity;
import com.loopers.infrastructure.persistence.ordering.entity.ProductPurchaseActivityJpaEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PurchaseActivityJpaRepository implements PurchaseActivityRepository {
    private final EntityManager entityManager;

    @Override
    public List<OrderConfirmation> findCandidates(UUID eventId, long orderId) {
        return entityManager.createQuery("""
            SELECT inbox FROM OrderConfirmationInboxJpaEntity inbox
            WHERE inbox.eventId = :eventId OR inbox.orderId = :orderId
            """, OrderConfirmationInboxJpaEntity.class)
            .setParameter("eventId", eventId.toString())
            .setParameter("orderId", orderId)
            .getResultList().stream()
            .map(OrderConfirmationInboxMapper::toDomain)
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertInbox(OrderConfirmation confirmation) {
        var inbox = OrderConfirmationInboxMapper.toEntity(confirmation);
        try {
            entityManager.persist(inbox);
            entityManager.flush();
            return inbox.getId();
        } catch (RuntimeException exception) {
            if (isInboxDuplicate(exception)) {
                throw new InboxInsertConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertActivities(long inboxId, OrderConfirmation confirmation) {
        var inbox = entityManager.getReference(OrderConfirmationInboxJpaEntity.class, inboxId);
        for (var item : confirmation.items()) {
            entityManager.persist(new ProductPurchaseActivityJpaEntity(inbox, confirmation.orderId(),
                item.productId(), item.quantity(), confirmation.confirmedAt()));
        }
        entityManager.flush();
    }

    private boolean isInboxDuplicate(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                && violation.getSQLException().getErrorCode() == 1062
                && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().replace("`", "").replace("'", "").replace("\"", "");
                String key = name.substring(name.lastIndexOf('.') + 1);
                return "uk_purchase_inbox_event_id".equals(key) || "uk_purchase_inbox_order_id".equals(key);
            }
        }
        return false;
    }
}
