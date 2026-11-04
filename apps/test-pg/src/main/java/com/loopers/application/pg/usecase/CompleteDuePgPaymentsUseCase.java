package com.loopers.application.pg.usecase;

import com.loopers.domain.pg.model.PgTransaction;
import java.time.Instant;
import java.util.List;

public interface CompleteDuePgPaymentsUseCase {
    List<PgTransaction> execute(Instant now);
}
