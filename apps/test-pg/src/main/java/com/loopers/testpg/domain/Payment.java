package com.loopers.testpg.domain;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Payment {
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{6,64}$");

    private final String transactionKey;
    private final String orderId;
    private final long amount;
    private final URI callbackUrl;
    private final PaymentStatus status;
    private final PaymentStatus completionResult;
    private final int callbackCount;

    private Payment(String transactionKey, String orderId, long amount, URI callbackUrl, PaymentStatus status,
                    PaymentStatus completionResult, int callbackCount) {
        this.transactionKey = transactionKey;
        this.orderId = orderId;
        this.amount = amount;
        this.callbackUrl = callbackUrl;
        this.status = status;
        this.completionResult = completionResult;
        this.callbackCount = callbackCount;
    }

    public static Payment create(String orderId, long amount, URI callbackUrl) {
        return create(orderId, amount, callbackUrl, ScenarioSetting.defaultSetting());
    }

    public static Payment create(String orderId, long amount, URI callbackUrl, ScenarioSetting scenario) {
        if (!isValidOrderId(orderId) || amount <= 0) {
            throw new IllegalArgumentException("주문 ID와 금액이 올바르지 않습니다.");
        }
        if (callbackUrl == null || callbackUrl.getHost() == null || callbackUrl.getUserInfo() != null
            || !("http".equalsIgnoreCase(callbackUrl.getScheme()) || "https".equalsIgnoreCase(callbackUrl.getScheme()))) {
            throw new IllegalArgumentException("callback URL이 올바르지 않습니다.");
        }
        if (scenario == null) {
            throw new IllegalArgumentException("접수 시나리오는 필수입니다.");
        }
        return new Payment("TEST-TX-" + UUID.randomUUID(), orderId, amount, callbackUrl, PaymentStatus.PENDING,
            scenario.result(), scenario.callbackCount());
    }

    public static boolean isValidOrderId(String orderId) {
        return orderId != null && ORDER_ID_PATTERN.matcher(orderId).matches();
    }

    public Payment succeed() {
        if (status != PaymentStatus.PENDING) {
            return this;
        }
        return new Payment(transactionKey, orderId, amount, callbackUrl, PaymentStatus.SUCCESS, completionResult, callbackCount);
    }

    public Payment fail() {
        if (status != PaymentStatus.PENDING) {
            return this;
        }
        return new Payment(transactionKey, orderId, amount, callbackUrl, PaymentStatus.FAILED, completionResult, callbackCount);
    }

    public String getTransactionKey() { return transactionKey; }
    public String getOrderId() { return orderId; }
    public long getAmount() { return amount; }
    public URI getCallbackUrl() { return callbackUrl; }
    public PaymentStatus getStatus() { return status; }
    public PaymentStatus getCompletionResult() { return completionResult; }
    public int getCallbackCount() { return callbackCount; }
}
