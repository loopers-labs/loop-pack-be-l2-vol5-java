package com.loopers.application.pg.result;

import com.loopers.domain.pg.model.PgTransaction;

public record CreatePgPaymentResult(PgTransaction transaction, boolean rejected) {}
