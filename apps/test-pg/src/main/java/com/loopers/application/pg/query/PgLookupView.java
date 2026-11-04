package com.loopers.application.pg.query;

import com.loopers.domain.pg.model.PgScenario;

public record PgLookupView(PgPaymentView payment, PgScenario.ResponseMode responseMode, long responseDelayMs) {}
