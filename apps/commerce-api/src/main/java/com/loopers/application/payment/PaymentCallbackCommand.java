package com.loopers.application.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Set;
import java.util.regex.Pattern;

public record PaymentCallbackCommand(String providerOrderId, String transactionKey, String status) {

    private static final Pattern TRANSACTION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    private static final Set<String> STATUSES = Set.of("PENDING", "SUCCESS", "FAILED");

    public PaymentCallbackCommand {
        if (providerOrderId == null || providerOrderId.isBlank() || providerOrderId.length() < 6
            || transactionKey == null || !TRANSACTION_KEY.matcher(transactionKey).matches()
            || status == null || !STATUSES.contains(status)) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }
}
