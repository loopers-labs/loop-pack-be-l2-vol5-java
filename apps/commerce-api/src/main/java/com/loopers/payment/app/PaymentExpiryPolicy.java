package com.loopers.payment.app;

import java.time.Duration;

public record PaymentExpiryPolicy(Duration timeout) {
}
