package com.loopers.domain.payment;

import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.RuleViolationException;

public final class PaymentRecord {
    public enum Method { POINT, PG }

    public enum Result { PENDING, SUCCESS, FAILED }

    public enum ProviderStatus {
        SEND_STARTED, NOT_SENT, PENDING, SUCCESS, FAILED, REJECTED_NO_EFFECT, UNKNOWN, ALL_NOT_SENT, NEEDS_REVIEW
    }

    private final Long id;
    private final long orderId;
    private final Method method;
    private final Money amount;
    private Result result;
    private final String attemptKey;
    private String providerId;
    private final String providerOrderId;
    private String transactionKey;
    private ProviderStatus providerStatus;

    private PaymentRecord(Long id, long orderId, Method method, Money amount, Result result, String attemptKey,
        String providerId, String providerOrderId, String transactionKey, ProviderStatus providerStatus) {
        if (orderId <= 0) {
            throw new InvalidValueException("결제 기록의 주문 ID는 양수여야 합니다.");
        }
        if (method == null || amount == null || result == null) {
            throw new InvalidValueException("결제 기록에는 결제수단·결제액·결과가 필요합니다.");
        }
        this.id = id;
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.result = result;
        this.attemptKey = attemptKey;
        this.providerId = providerId;
        this.providerOrderId = providerOrderId;
        this.transactionKey = transactionKey;
        this.providerStatus = providerStatus;
    }

    public static PaymentRecord point(long orderId, Money amount) {
        return new PaymentRecord(null, orderId, Method.POINT, amount, Result.SUCCESS, null, null, null, null, null);
    }

    // 발송 시작은 "보냈을 수도 있음"이다. 이 상태로 남은 기록은 create를 다시 보내지 않고 조회로만 확인한다(설계 23절).
    public static PaymentRecord pgStarted(long orderId, Money amount, String attemptKey, String providerId) {
        if (attemptKey == null || attemptKey.isBlank() || providerId == null || providerId.isBlank()) {
            throw new InvalidValueException("PG 결제 기록에는 시도 키와 providerId가 필요합니다.");
        }
        return new PaymentRecord(null, orderId, Method.PG, amount, Result.PENDING, attemptKey, providerId,
            providerOrderIdOf(orderId), null, ProviderStatus.SEND_STARTED);
    }

    // 실패 후 재결제가 생기면 같은 주문의 시도를 구분하도록 시도 번호를 붙여야 한다(설계 23절).
    private static String providerOrderIdOf(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    public static PaymentRecord restore(long id, long orderId, Method method, Money amount, Result result, String attemptKey,
        String providerId, String providerOrderId, String transactionKey, ProviderStatus providerStatus) {
        return new PaymentRecord(id, orderId, method, amount, result, attemptKey, providerId, providerOrderId, transactionKey,
            providerStatus);
    }

    public record TransactionMatch(boolean orderMatches, boolean amountMatches, boolean keyMatches) {
        public boolean matches() {
            return orderMatches && amountMatches && keyMatches;
        }
    }

    public TransactionMatch compareTransaction(String reportedOrderId, Money reportedAmount, String reportedKey) {
        return new TransactionMatch(providerOrderId.equals(reportedOrderId), amount.equals(reportedAmount),
            transactionKey == null || transactionKey.equals(reportedKey));
    }

    public void switchProvider(String nextProviderId) {
        if (method != Method.PG || result != Result.PENDING || nextProviderId == null || nextProviderId.isBlank()) {
            throw new RuleViolationException("결제 대기 중인 PG 기록만 다음 PG로 전환할 수 있습니다.");
        }
        providerId = nextProviderId;
        providerStatus = ProviderStatus.SEND_STARTED;
    }

    public void requireReview() {
        if (method == Method.PG && result == Result.PENDING) {
            providerStatus = ProviderStatus.NEEDS_REVIEW;
        }
    }

    public void applyProviderReport(ProviderStatus status, String reportedTransactionKey) {
        if (method != Method.PG || result != Result.PENDING) {
            throw new RuleViolationException("결과가 정해진 결제 기록에는 PG 보고를 반영하지 않습니다.");
        }
        providerStatus = status;
        if (reportedTransactionKey != null) {
            transactionKey = reportedTransactionKey;
        }
        result = switch (status) {
            case SUCCESS -> Result.SUCCESS;
            case FAILED, REJECTED_NO_EFFECT, ALL_NOT_SENT -> Result.FAILED;
            case SEND_STARTED, NOT_SENT, PENDING, UNKNOWN, NEEDS_REVIEW -> Result.PENDING;
        };
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public Method getMethod() {
        return method;
    }

    public Money getAmount() {
        return amount;
    }

    public Result getResult() {
        return result;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public ProviderStatus getProviderStatus() {
        return providerStatus;
    }
}
