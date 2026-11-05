package com.loopers.domains.payment.app;

import java.time.Duration;

public record PaymentExpiryPolicy(Duration timeout) {
}
