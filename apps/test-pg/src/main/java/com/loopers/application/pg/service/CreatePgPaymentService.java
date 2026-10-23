package com.loopers.application.pg.service;

import com.loopers.application.pg.command.CreatePgPaymentCommand;
import com.loopers.application.pg.result.CreatePgPaymentResult;
import com.loopers.application.pg.usecase.CreatePgPaymentUseCase;
import com.loopers.domain.pg.model.PgTransaction;
import com.loopers.domain.pg.repository.PgTransactionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreatePgPaymentService implements CreatePgPaymentUseCase {
    private final PgTransactionRepository repository;

    @Override
    @Transactional
    public CreatePgPaymentResult execute(CreatePgPaymentCommand command) {
        if (command.scenario().rejectBeforeCreate()) {
            return new CreatePgPaymentResult(null, true);
        }
        PgTransaction transaction = PgTransaction.create(command.orderId(), command.amount(), command.callbackUrl(),
                command.scenario(), Instant.now());
        repository.insert(transaction);
        return new CreatePgPaymentResult(transaction, false);
    }
}
