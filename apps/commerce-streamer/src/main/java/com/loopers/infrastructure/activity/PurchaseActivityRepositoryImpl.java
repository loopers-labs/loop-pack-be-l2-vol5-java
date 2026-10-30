package com.loopers.infrastructure.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.activity.AlreadyHeld;
import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.FactAlreadyRecorded;
import com.loopers.domain.activity.OrderEventHold;
import com.loopers.domain.activity.PurchaseActivityRepository;
import com.loopers.domain.activity.RecordedConfirmation;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Repository
@RequiredArgsConstructor
class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {

    private static final Set<String> RECEIPT_CONSTRAINTS = Set.of("uk_receipt_event", "uk_receipt_order");
    private static final Set<String> HOLD_CONSTRAINTS = Set.of("uk_hold_position");

    private final OrderConfirmationReceiptJpaRepository receiptJpaRepository;
    private final PurchaseActivityJpaRepository activityJpaRepository;
    private final OrderEventHoldJpaRepository holdJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void saveReceipt(ConfirmedPurchase purchase) {
        try {
            receiptJpaRepository.saveAndFlush(OrderConfirmationReceiptEntity.of(purchase));
        } catch (DataIntegrityViolationException e) {
            if (violatesAnyOf(e, RECEIPT_CONSTRAINTS)) {
                throw new FactAlreadyRecorded(e);
            }
            throw e;
        }
    }

    @Override
    public void saveActivities(ConfirmedPurchase purchase) {
        activityJpaRepository.saveAllAndFlush(purchase.items().stream()
            .map(item -> PurchaseActivityEntity.of(
                purchase.orderId(), item.productId(), item.quantity(), purchase.confirmedAt()))
            .toList());
    }

    @Override
    public Optional<RecordedConfirmation> findReceiptByEventId(String eventId) {
        return receiptJpaRepository.findByEventId(eventId).map(OrderConfirmationReceiptEntity::toRecorded);
    }

    @Override
    public Optional<RecordedConfirmation> findReceiptByOrderId(Long orderId) {
        return receiptJpaRepository.findByOrderId(orderId).map(OrderConfirmationReceiptEntity::toRecorded);
    }

    @Override
    public void saveHold(OrderEventHold hold) {
        try {
            holdJpaRepository.saveAndFlush(OrderEventHoldEntity.from(hold, toJson(hold.record().headers())));
        } catch (DataIntegrityViolationException e) {
            if (violatesAnyOf(e, HOLD_CONSTRAINTS)) {
                throw new AlreadyHeld(e);
            }
            throw e;
        }
    }

    private String toJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(headers));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("보류할 record 의 header 를 적지 못했다", e);
        }
    }

    private static boolean violatesAnyOf(DataIntegrityViolationException e, Set<String> constraints) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName().toLowerCase(Locale.ROOT);
                String bare = name.substring(name.lastIndexOf('.') + 1);
                return constraints.contains(bare);
            }
        }
        return false;
    }
}
