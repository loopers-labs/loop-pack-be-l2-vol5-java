package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.command.HandlePaymentCallbackCommand;
import com.loopers.application.ordering.result.PaymentCallbackResult;

public interface HandlePaymentCallbackUseCase {
    PaymentCallbackResult execute(HandlePaymentCallbackCommand command);
}
