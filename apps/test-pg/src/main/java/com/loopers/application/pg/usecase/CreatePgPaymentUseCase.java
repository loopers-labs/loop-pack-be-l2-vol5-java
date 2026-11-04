package com.loopers.application.pg.usecase;

import com.loopers.application.pg.command.CreatePgPaymentCommand;
import com.loopers.application.pg.result.CreatePgPaymentResult;

public interface CreatePgPaymentUseCase {
    CreatePgPaymentResult execute(CreatePgPaymentCommand command);
}
