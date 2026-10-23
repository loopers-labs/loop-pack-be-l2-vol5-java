package com.loopers.domain.payment.service;

import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.enums.ReservationChange;
import com.loopers.domain.payment.repository.PaymentRepository;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.vo.PaymentLookupResult;

@RequiredArgsConstructor
@Component
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Payment getByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    /** 목록 응답용. 결제 시도가 없는 주문(DRAFT · 이전 POINT 주문)은 맵에 없음 */
    @Transactional(readOnly = true)
    public Map<Long, Payment> findByOrderIds(Collection<Long> orderIds) {
        return paymentRepository.findAllByOrderIds(orderIds).stream()
            .collect(Collectors.toMap(Payment::getOrderId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Payment getByProviderOrderId(String providerOrderId) {
        return paymentRepository.findByProviderOrderId(providerOrderId)
            .orElseThrow(() -> new CoreException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    /** 재고를 다루기 전에 부름 */
    @Transactional(readOnly = true)
    public void validateAttemptKeyAvailable(String attemptKey) {
        if (paymentRepository.findByAttemptKey(attemptKey).isPresent()) {
            throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    /** 사전 확인 뒤 같은 키가 먼저 저장되면 유일 제약이 막아 TX 전체가 rollback 됨 */
    @Transactional
    public Payment request(Long orderId, String attemptKey, long amount) {
        try {
            return paymentRepository.save(Payment.request(orderId, attemptKey, amount));
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    @Transactional
    public Payment recordNotRequired(Long orderId, String attemptKey) {
        try {
            return paymentRepository.save(Payment.notRequired(orderId, attemptKey));
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    @Transactional
    public ReservationChange apply(Payment payment, PaymentCreateResult result) {
        return payment.apply(result, ZonedDateTime.now(clock));
    }

    @Transactional
    public ReservationChange apply(Payment payment, PaymentLookupResult result) {
        return payment.apply(result, ZonedDateTime.now(clock));
    }
}
