package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;

// 주문 확정 유스케이스
public interface ConfirmOrderUseCase {
    ConfirmOrderResult execute(ConfirmOrderCommand command);
}
