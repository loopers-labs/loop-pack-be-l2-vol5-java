package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.DuplicatePaymentAttemptException;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.infrastructure.support.MySqlConstraintErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private static final List<PaymentAttemptStatus> ACCEPTABLE =
        List.of(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.UNKNOWN);
    private static final List<PaymentAttemptStatus> UNTERMINATED =
        List.of(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.PENDING, PaymentAttemptStatus.UNKNOWN);

    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    // 시도는 TX A에서 새로 저장할 때만 save를 쓰고, 이후 상태는 조건부 갱신으로만 바꾼다.
    // 유일 제약 충돌을 저장소 경계에서 드러내기 위해 flush까지 실행한다.
    // 시도 키·주문의 충돌만 재요청 판정 대상으로 알리고, PG 주문 식별자 충돌 등은 기술 오류로 둔다.
    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        if (attempt.getId() != null) {
            throw new IllegalArgumentException("저장된 결제 시도는 조건부 갱신으로만 바꾼다: " + attempt.getId());
        }
        try {
            PaymentAttemptJpaEntity saved = paymentAttemptJpaRepository.saveAndFlush(
                PaymentAttemptJpaMapper.toNewEntity(attempt)
            );
            return PaymentAttemptJpaMapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            if (MySqlConstraintErrors.isDuplicateKey(exception, "uk_payment_attempts_key")
                || MySqlConstraintErrors.isDuplicateKey(exception, "uk_payment_attempts_order_id")) {
                throw new DuplicatePaymentAttemptException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<PaymentAttempt> findById(Long attemptId) {
        return paymentAttemptJpaRepository.findById(attemptId).map(PaymentAttemptJpaMapper::toDomain);
    }

    @Override
    public Optional<PaymentAttempt> findByPaymentAttemptKey(String paymentAttemptKey) {
        return paymentAttemptJpaRepository.findByPaymentAttemptKey(paymentAttemptKey)
            .map(PaymentAttemptJpaMapper::toDomain);
    }

    @Override
    public Optional<PaymentAttempt> findByOrderId(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).map(PaymentAttemptJpaMapper::toDomain);
    }

    @Override
    public List<PaymentAttempt> findAllByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentAttemptJpaRepository.findAllByOrderIdIn(orderIds).stream()
            .map(PaymentAttemptJpaMapper::toDomain)
            .toList();
    }

    @Override
    public Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId)
            .map(PaymentAttemptJpaMapper::toDomain);
    }

    @Override
    public int markPending(Long attemptId, String transactionKey, ZonedDateTime changedAt) {
        return paymentAttemptJpaRepository.updateStatusWithTransactionKey(
            attemptId, ACCEPTABLE, PaymentAttemptStatus.PENDING, requireTransactionKey(transactionKey), changedAt
        );
    }

    @Override
    public int markUnknown(Long attemptId, ZonedDateTime changedAt) {
        return paymentAttemptJpaRepository.updateStatusWithoutTransaction(
            attemptId, List.of(PaymentAttemptStatus.REQUESTED), PaymentAttemptStatus.UNKNOWN, changedAt
        );
    }

    @Override
    public int terminate(Long attemptId, PaymentAttemptStatus terminalStatus, String transactionKey,
                         ZonedDateTime changedAt) {
        if (terminalStatus == null || !terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("종결 상태로만 종결할 수 있다: " + terminalStatus);
        }
        return paymentAttemptJpaRepository.updateStatusWithTransactionKey(
            attemptId, UNTERMINATED, terminalStatus, requireTransactionKey(transactionKey), changedAt
        );
    }

    @Override
    public int rejectBeforeCreate(Long attemptId, ZonedDateTime changedAt) {
        return paymentAttemptJpaRepository.updateStatusWithoutTransaction(
            attemptId, ACCEPTABLE, PaymentAttemptStatus.FAILED, changedAt
        );
    }

    @Override
    public int recordConflict(Long attemptId, PaymentConflictReason reason, ZonedDateTime detectedAt) {
        return paymentAttemptJpaRepository.recordConflict(attemptId, reason, detectedAt);
    }

    @Override
    public int touchLastChecked(Long attemptId, ZonedDateTime checkedAt) {
        return paymentAttemptJpaRepository.touchLastChecked(attemptId, checkedAt);
    }

    @Override
    public List<PaymentAttempt> findDueForCheck(ZonedDateTime changedBefore, ZonedDateTime createdAfter, int limit) {
        return paymentAttemptJpaRepository.findDueForCheck(
                UNTERMINATED, changedBefore, createdAfter, PageRequest.of(0, limit)
            ).stream()
            .map(PaymentAttemptJpaMapper::toDomain)
            .toList();
    }

    private static String requireTransactionKey(String transactionKey) {
        if (transactionKey == null || transactionKey.isBlank()) {
            throw new IllegalArgumentException("PG 거래 키는 필수입니다.");
        }
        return transactionKey;
    }
}
