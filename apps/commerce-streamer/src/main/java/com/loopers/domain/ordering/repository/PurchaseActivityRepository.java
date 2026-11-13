package com.loopers.domain.ordering.repository;

import com.loopers.domain.ordering.model.OrderConfirmation;
import java.util.List;
import java.util.UUID;

public interface PurchaseActivityRepository {
    List<OrderConfirmation> findCandidates(UUID eventId, long orderId);

    long insertInbox(OrderConfirmation confirmation);

    void insertActivities(long inboxId, OrderConfirmation confirmation);

    class InboxInsertConflictException extends RuntimeException {
        public InboxInsertConflictException(Throwable cause) {
            super("Inbox 유일 제약이 충돌했습니다.", cause);
        }
    }
}
