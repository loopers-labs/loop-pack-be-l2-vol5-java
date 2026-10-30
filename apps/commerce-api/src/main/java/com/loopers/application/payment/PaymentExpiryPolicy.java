package com.loopers.application.payment;

import java.time.Duration;

public record PaymentExpiryPolicy(Duration timeout) {
}
