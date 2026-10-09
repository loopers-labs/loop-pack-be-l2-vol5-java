package com.loopers.application.ordering.usecase;

import com.loopers.application.ordering.command.OrderCommand;
import com.loopers.application.ordering.result.OrderResult;

// 주문 생성 유스케이스
public interface CreateOrderUseCase {
    OrderResult execute(OrderCommand.Create command);
}
