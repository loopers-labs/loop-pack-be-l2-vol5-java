package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.payment.QPaymentAttempt.paymentAttempt;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        return paymentAttemptJpaRepository.save(attempt);
    }

    @Override
    public Optional<PaymentAttempt> findById(Long id) {
        return paymentAttemptJpaRepository.findById(id);
    }

    @Override
    public Optional<PaymentAttempt> findByOrderId(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentAttempt> findByUserIdAndAttemptKey(Long userId, String attemptKey) {
        return paymentAttemptJpaRepository.findByUserIdAndAttemptKey(userId, attemptKey);
    }

    /**
     * transactionKey 가 null 이면(생성 전 거절) 기존 값을 건드리지 않는다.
     */
    @Override
    public int settleIfOpen(
        Long attemptId,
        PaymentAttempt.Status status,
        PaymentAttempt.FailureReason failureReason,
        String transactionKey
    ) {
        JPAUpdateClause update = queryFactory
            .update(paymentAttempt)
            .set(paymentAttempt.status, status)
            .set(paymentAttempt.failureReason, failureReason)
            .set(paymentAttempt.updatedAt, ZonedDateTime.now());
        if (transactionKey != null) {
            update.set(paymentAttempt.transactionKey, transactionKey);
        }
        return (int) update
            .where(
                paymentAttempt.id.eq(attemptId),
                paymentAttempt.status.in(
                    PaymentAttempt.Status.REQUESTED, PaymentAttempt.Status.PENDING, PaymentAttempt.Status.UNKNOWN)
            )
            .execute();
    }

    @Override
    public int markPendingIfNotSettled(Long attemptId, String transactionKey) {
        return (int) queryFactory
            .update(paymentAttempt)
            .set(paymentAttempt.status, PaymentAttempt.Status.PENDING)
            .set(paymentAttempt.transactionKey, transactionKey)
            .set(paymentAttempt.updatedAt, ZonedDateTime.now())
            .where(
                paymentAttempt.id.eq(attemptId),
                paymentAttempt.status.in(PaymentAttempt.Status.REQUESTED, PaymentAttempt.Status.UNKNOWN)
            )
            .execute();
    }

    @Override
    public int markUnknownIfRequested(Long attemptId) {
        return (int) queryFactory
            .update(paymentAttempt)
            .set(paymentAttempt.status, PaymentAttempt.Status.UNKNOWN)
            .set(paymentAttempt.updatedAt, ZonedDateTime.now())
            .where(
                paymentAttempt.id.eq(attemptId),
                paymentAttempt.status.eq(PaymentAttempt.Status.REQUESTED)
            )
            .execute();
    }

    @Override
    public Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId);
    }

    @Override
    public List<PaymentAttempt> findAllOpenDueForLookup(ZonedDateTime updatedBefore, ZonedDateTime now) {
        return queryFactory
            .selectFrom(paymentAttempt)
            .where(
                paymentAttempt.status.in(
                    PaymentAttempt.Status.REQUESTED, PaymentAttempt.Status.PENDING, PaymentAttempt.Status.UNKNOWN),
                paymentAttempt.manualCheckRequired.isFalse(),
                paymentAttempt.updatedAt.loe(updatedBefore),
                paymentAttempt.nextLookupAt.isNull().or(paymentAttempt.nextLookupAt.loe(now))
            )
            .orderBy(paymentAttempt.id.asc())
            .fetch();
    }

    /**
     * 상태 컬럼은 건드리지 않는다. 마지막 충돌 사유만 덮어쓴다.
     */
    @Override
    public int recordConflict(Long attemptId, PaymentAttempt.ConflictReason reason, ZonedDateTime at) {
        return (int) queryFactory
            .update(paymentAttempt)
            .set(paymentAttempt.conflicted, true)
            .set(paymentAttempt.conflictReason, reason)
            .set(paymentAttempt.conflictedAt, at)
            .set(paymentAttempt.updatedAt, ZonedDateTime.now())
            .where(paymentAttempt.id.eq(attemptId))
            .execute();
    }

    /**
     * updatedAt 은 갱신하지 않는다. 조회 기록은 업무 상태 변경이 아니고, min-age 는 마지막 업무 변경 기준이기 때문이다.
     */
    @Override
    public int recordLookup(Long attemptId, ZonedDateTime nextLookupAt, boolean manualCheckRequired) {
        return (int) queryFactory
            .update(paymentAttempt)
            .set(paymentAttempt.lookupCount, paymentAttempt.lookupCount.add(1))
            .set(paymentAttempt.nextLookupAt, nextLookupAt)
            .set(paymentAttempt.manualCheckRequired, manualCheckRequired)
            .where(paymentAttempt.id.eq(attemptId))
            .execute();
    }
}