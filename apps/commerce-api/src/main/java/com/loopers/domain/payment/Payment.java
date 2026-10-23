package com.loopers.domain.payment;

import com.loopers.domain.common.Money;

import java.time.Instant;
import java.util.List;

public class Payment {

    private final Long id;
    private final Long orderId;
    private final PaymentAttemptKey attemptKey;
    private final ProviderOrderId providerOrderId;
    private final Money amount;
    private final CardType cardType;
    private final Instant requestedAt;

    private PaymentStatus status;
    private TransactionKey transactionKey;

    private Payment(Long id, Long orderId, PaymentAttemptKey attemptKey, ProviderOrderId providerOrderId,
                    Money amount, CardType cardType, Instant requestedAt,
                    PaymentStatus status, TransactionKey transactionKey) {
        this.id = id;
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.cardType = cardType;
        this.requestedAt = requestedAt;
        this.status = status;
        this.transactionKey = transactionKey;
    }

    public static Payment request(Long orderId, PaymentAttemptKey attemptKey, Money amount,
                                  CardType cardType, Instant requestedAt) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId 는 필수입니다");
        }
        return new Payment(null, orderId, attemptKey, ProviderOrderId.of(orderId), amount, cardType, requestedAt,
            PaymentStatus.REQUESTED, null);
    }

    public static Payment restore(Long id, Long orderId, PaymentAttemptKey attemptKey, ProviderOrderId providerOrderId,
                                  Money amount, CardType cardType, Instant requestedAt,
                                  PaymentStatus status, TransactionKey transactionKey) {
        return new Payment(id, orderId, attemptKey, providerOrderId, amount, cardType, requestedAt,
            status, transactionKey);
    }

    public boolean isSameRequest(Long orderId, Money amount, CardType cardType) {
        return this.orderId.equals(orderId) && this.amount.equals(amount) && this.cardType == cardType;
    }

    public PaymentOutcome observe(CreateObservation observation) {
        if (transactionKey != null) {
            if (observation instanceof CreateObservation.Accepted(TransactionKey key)
                && !key.equals(transactionKey)) {
                return contradict(PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS,
                    key, TransactionStatus.PENDING, null);
            }
            return PaymentOutcome.none();
        }
        return switch (observation) {
            case CreateObservation.Accepted accepted -> {
                this.transactionKey = accepted.transactionKey();
                this.status = PaymentStatus.PENDING;
                yield PaymentOutcome.none();
            }
            case CreateObservation.RejectedNoEffect rejected -> fail();
            case CreateObservation.NotSent notSent -> fail();
            case CreateObservation.CreateUnknown unknown -> {
                this.status = PaymentStatus.UNKNOWN;
                yield PaymentOutcome.none();
            }
        };
    }

    public PaymentOutcome observe(LookupObservation observation) {
        return switch (observation) {
            case LookupResult.NotFound notFound -> PaymentOutcome.none();
            case LookupResult.Found found -> observeFound(found);
        };
    }

    private PaymentOutcome observeFound(LookupResult.Found found) {
        List<PgTransaction> transactions = found.transactions();
        if (transactions.size() > 1) {
            PgTransaction other = transactions.stream()
                .filter(candidate -> !candidate.transactionKey().equals(transactionKey))
                .findFirst()
                .orElseThrow();
            return contradict(PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS,
                other.transactionKey(), other.status(), other.amount());
        }
        PgTransaction transaction = transactions.getFirst();
        if (!transaction.providerOrderId().equals(providerOrderId)) {
            return contradict(PaymentDiscrepancy.Reason.ORDER_MISMATCH,
                transaction.transactionKey(), transaction.status(), transaction.amount());
        }
        if (!transaction.amount().equals(amount)) {
            return contradict(PaymentDiscrepancy.Reason.AMOUNT_MISMATCH,
                transaction.transactionKey(), transaction.status(), transaction.amount());
        }
        if (transactionKey != null && !transaction.transactionKey().equals(transactionKey)) {
            return contradict(PaymentDiscrepancy.Reason.MULTIPLE_TRANSACTIONS,
                transaction.transactionKey(), transaction.status(), transaction.amount());
        }
        if (status == PaymentStatus.CONFLICT) {
            return PaymentOutcome.none();
        }
        if (isSettled()) {
            if (isSameResult(transaction.status())) {
                return PaymentOutcome.none();
            }
            return contradict(PaymentDiscrepancy.Reason.RESULT_AFTER_SETTLED,
                transaction.transactionKey(), transaction.status(), transaction.amount());
        }
        if (transactionKey == null) {
            this.transactionKey = transaction.transactionKey();
        }
        return switch (transaction.status()) {
            case PENDING -> {
                this.status = PaymentStatus.PENDING;
                yield PaymentOutcome.none();
            }
            case SUCCESS -> {
                this.status = PaymentStatus.SUCCESS;
                yield PaymentOutcome.confirmOrder();
            }
            case FAILED -> fail();
        };
    }

    private PaymentOutcome contradict(PaymentDiscrepancy.Reason reason, TransactionKey observedKey,
                                      TransactionStatus observedStatus, Money observedAmount) {
        if (!isSettled()) {
            this.status = PaymentStatus.CONFLICT;
        }
        return PaymentOutcome.conflict(new PaymentDiscrepancy(reason, observedKey, observedStatus, observedAmount));
    }

    private boolean isSettled() {
        return status == PaymentStatus.SUCCESS || status == PaymentStatus.FAILED;
    }

    private boolean isSameResult(TransactionStatus observed) {
        return status == PaymentStatus.SUCCESS && observed == TransactionStatus.SUCCESS
            || status == PaymentStatus.FAILED && observed == TransactionStatus.FAILED;
    }

    private PaymentOutcome fail() {
        this.status = PaymentStatus.FAILED;
        return PaymentOutcome.failOrder();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PaymentAttemptKey getAttemptKey() {
        return attemptKey;
    }

    public ProviderOrderId getProviderOrderId() {
        return providerOrderId;
    }

    public Money getAmount() {
        return amount;
    }

    public CardType getCardType() {
        return cardType;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public TransactionKey getTransactionKey() {
        return transactionKey;
    }
}
