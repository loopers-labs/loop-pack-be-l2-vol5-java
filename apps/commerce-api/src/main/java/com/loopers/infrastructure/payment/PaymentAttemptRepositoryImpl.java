package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.UnresolvedStatusSummary;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.payment.QPaymentAttempt.paymentAttempt;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {

    /** 미종결 상태. 종결(SUCCESS·FAILED)은 미확인 집계에서 제외한다. */
    private static final List<PaymentAttempt.Status> UNRESOLVED_STATUSES = List.of(
        PaymentAttempt.Status.REQUESTED, PaymentAttempt.Status.PENDING, PaymentAttempt.Status.UNKNOWN);

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

    /**
     * 집계는 GROUP BY 한 번으로 끝낸다. 상태마다 쿼리를 날리면 같은 시점의 값이 아니게 되고 왕복도 늘어난다.
     * 건수와 가장 오래된 생성 시각을 같은 쿼리에서 함께 뽑는다.
     * 행이 없는 상태는 결과에 나오지 않으므로, 세 상태를 순서대로 돌며 조회 결과가 없으면 count 0 · 시각 null 로 채운다.
     * 0 이나 epoch 로 시각을 채우지 않는다. 1970년부터 미확인이 있는 것처럼 보이기 때문이다.
     */
    @Override
    public List<UnresolvedStatusSummary> summarizeUnresolved() {
        List<Tuple> rows = queryFactory
            .select(paymentAttempt.status, paymentAttempt.count(), paymentAttempt.createdAt.min())
            .from(paymentAttempt)
            .where(paymentAttempt.status.in(UNRESOLVED_STATUSES))
            .groupBy(paymentAttempt.status)
            .fetch();

        List<UnresolvedStatusSummary> summaries = new ArrayList<>(UNRESOLVED_STATUSES.size());
        for (PaymentAttempt.Status status : UNRESOLVED_STATUSES) {
            summaries.add(rows.stream()
                .filter(row -> status == row.get(paymentAttempt.status))
                .findFirst()
                .map(row -> new UnresolvedStatusSummary(
                    status, row.get(paymentAttempt.count()), row.get(paymentAttempt.createdAt.min())))
                .orElseGet(() -> new UnresolvedStatusSummary(status, 0L, null)));
        }
        return summaries;
    }
}