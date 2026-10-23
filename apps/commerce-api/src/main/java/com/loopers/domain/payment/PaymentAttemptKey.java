package com.loopers.domain.payment;

import java.util.regex.Pattern;

public record PaymentAttemptKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public PaymentAttemptKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("결제 시도 키는 1~64자의 영문, 숫자, - 여야 합니다: " + value);
        }
    }

    public static PaymentAttemptKey of(String value) {
        return new PaymentAttemptKey(value);
    }
}
