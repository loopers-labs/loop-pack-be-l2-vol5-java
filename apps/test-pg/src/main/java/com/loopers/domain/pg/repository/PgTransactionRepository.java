package com.loopers.domain.pg.repository;

import com.loopers.domain.pg.model.PgTransaction;
import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface PgTransactionRepository {
    void insert(PgTransaction transaction);
    Optional<PgTransaction> findByKey(String transactionKey);
    List<PgTransaction> findDue(Instant now, int limit);
    boolean completeIfPending(PgTransaction completed, Instant now);
}
