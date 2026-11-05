package com.loopers.infrastructure.purchase;

import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchasedItem;
import com.loopers.application.purchase.port.DuplicateConfirmationException;
import com.loopers.application.purchase.port.PurchaseActivityRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PurchaseActivityPersistenceAdapter implements PurchaseActivityRepository {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final ProcessedOrderConfirmationJpaRepository processedOrderConfirmationJpaRepository;
    private final ProductPurchaseActivityJpaRepository productPurchaseActivityJpaRepository;

    public PurchaseActivityPersistenceAdapter(
        ProcessedOrderConfirmationJpaRepository processedOrderConfirmationJpaRepository,
        ProductPurchaseActivityJpaRepository productPurchaseActivityJpaRepository
    ) {
        this.processedOrderConfirmationJpaRepository = processedOrderConfirmationJpaRepository;
        this.productPurchaseActivityJpaRepository = productPurchaseActivityJpaRepository;
    }

    @Override
    public Optional<ProcessedConfirmation> findConfirmationByEventId(String eventId) {
        return processedOrderConfirmationJpaRepository.findByEventId(eventId).map(this::toProcessedConfirmation);
    }

    @Override
    public Optional<ProcessedConfirmation> findConfirmationByOrderId(long orderId) {
        return processedOrderConfirmationJpaRepository.findByOrderId(orderId).map(this::toProcessedConfirmation);
    }

    @Override
    public void saveConfirmation(OrderConfirmedEvent event, Instant processedAt) {
        ProcessedOrderConfirmationJpaEntity confirmation = new ProcessedOrderConfirmationJpaEntity(
            event.eventId(), event.orderId(), event.fingerprint(), event.confirmedAt(), processedAt);
        try {
            processedOrderConfirmationJpaRepository.saveAndFlush(confirmation);
        } catch (DataIntegrityViolationException e) {
            // 유일 제약 위반만 중복으로 바꾼다. 다른 무결성 오류는 일시적 실패로 그대로 올려 재시도 대상이 되게 한다.
            if (isDuplicateEntry(e)) {
                throw new DuplicateConfirmationException(
                    "이미 완료 기록이 있음: eventId=" + event.eventId() + ", orderId=" + event.orderId(), e);
            }
            throw e;
        }
    }

    @Override
    public void saveActivity(long orderId, PurchasedItem item, Instant confirmedAt, Instant createdAt) {
        productPurchaseActivityJpaRepository.save(
            new ProductPurchaseActivityJpaEntity(orderId, item.productId(), item.quantity(), confirmedAt, createdAt));
    }

    private ProcessedConfirmation toProcessedConfirmation(ProcessedOrderConfirmationJpaEntity entity) {
        return new ProcessedConfirmation(entity.getEventId(), entity.getOrderId(), entity.getPayloadFingerprint());
    }

    private boolean isDuplicateEntry(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                return true;
            }
        }
        return false;
    }
}
