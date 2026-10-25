package com.loopers.testpg.application;

import java.time.Duration;

public sealed interface CreateResult {

    record Accepted(PaymentInfo payment, Duration responseDelay) implements CreateResult {}

    record RejectedBeforeCreate(String orderId, long amount) implements CreateResult {}

    record Overridden(int status, String body) implements CreateResult {}
}
