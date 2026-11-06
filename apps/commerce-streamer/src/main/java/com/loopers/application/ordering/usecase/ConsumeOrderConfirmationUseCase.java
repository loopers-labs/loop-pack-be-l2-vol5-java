package com.loopers.application.ordering.usecase;

import com.loopers.domain.ordering.model.OrderConfirmation;

public interface ConsumeOrderConfirmationUseCase {
    Result execute(OrderConfirmation confirmation);

    enum Result { CREATED, ALREADY_PROCESSED }
}
