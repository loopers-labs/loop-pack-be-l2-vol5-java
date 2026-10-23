package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.result.PrepareOrderPaymentResult;

public interface PrepareOrderPaymentUseCase {
    PrepareOrderPaymentResult execute(PrepareOrderPaymentCommand command);
}
