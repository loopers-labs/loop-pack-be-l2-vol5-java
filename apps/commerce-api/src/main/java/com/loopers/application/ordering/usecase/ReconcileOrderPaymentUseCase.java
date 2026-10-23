package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.command.ReconcileOrderPaymentCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;

public interface ReconcileOrderPaymentUseCase {
    ConfirmOrderResult execute(ReconcileOrderPaymentCommand command);
}
