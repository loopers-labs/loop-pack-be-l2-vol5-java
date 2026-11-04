package com.loopers.application.pg.service;

import com.loopers.application.pg.usecase.CompleteDuePgPaymentsUseCase;
import com.loopers.domain.pg.model.PgTransaction;
import com.loopers.domain.pg.repository.PgTransactionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompleteDuePgPaymentsService implements CompleteDuePgPaymentsUseCase {
    private final PgTransactionRepository repository;

    @Override
    @Transactional
    public List<PgTransaction> execute(Instant now) {
        List<PgTransaction> completed = new ArrayList<>();
        for (PgTransaction pending : repository.findDue(now, 100)) {
            PgTransaction result = pending.completeAt(now);
            if (repository.completeIfPending(result, now)) {
                completed.add(result);
            }
        }
        return List.copyOf(completed);
    }
}
