package com.loopers.application.pg.command;

import com.loopers.domain.pg.model.PgScenario;

public record CreatePgPaymentCommand(String orderId, long amount, String callbackUrl, PgScenario scenario) {}
