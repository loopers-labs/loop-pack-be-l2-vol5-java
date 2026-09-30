package com.loopers.application.ordering.service;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.dao.ConfirmOrderLoad;
import com.loopers.application.ordering.dao.ConfirmOrderWriter;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.OrderResult;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.policy.OrderConfirmationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 주문 확정 유스케이스 구현체
public class ConfirmOrderService implements ConfirmOrderUseCase {
    private final ConfirmOrderWriter confirmOrderWriter;

    // 재고 차감, 포인트 사용, 주문 기록을 한 트랜잭션으로 처리
    @Override
    @Transactional
    public ConfirmOrderResult execute(ConfirmOrderCommand command) {
        ConfirmOrderLoad load = confirmOrderWriter.load(command.orderId());
        OrderConfirmation confirmation =
            OrderConfirmationPolicy.confirm(load.order(), load.productsByProductId(), load.wallet());
        Order order = confirmation.order();
        OrderRecord record = order.getRecord().orElseThrow();

        confirmOrderWriter.save(load, confirmation.pointBill());

        return new ConfirmOrderResult(OrderResult.from(order), record.getAmount(), record.getStatus());
    }
}
