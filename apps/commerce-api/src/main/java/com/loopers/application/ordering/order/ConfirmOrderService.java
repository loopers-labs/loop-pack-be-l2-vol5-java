package com.loopers.application.ordering.order;

import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderConfirmation;
import com.loopers.domain.ordering.order.OrderConfirmationPolicy;
import com.loopers.domain.pay.orderbill.OrderBill;
import com.loopers.domain.pay.orderbill.OrderBillStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 주문 확정 유스케이스 구현체
public class ConfirmOrderService implements ConfirmOrderUseCase {
    private final ConfirmOrderWriter confirmOrderWriter;

    // 재고 차감, 포인트 사용, 결제 기록을 한 트랜잭션으로 처리
    @Override
    @Transactional
    public ConfirmOrderResult execute(ConfirmOrderCommand command) {
        ConfirmOrderLoad load = confirmOrderWriter.load(command.orderId());
        OrderConfirmation confirmation =
            OrderConfirmationPolicy.confirm(load.order(), load.productsByProductId(), load.wallet());
        Order order = confirmation.order();

        OrderBill orderBill = OrderBill.paid(order.getId(), order.getUserId(), order.getTotalAmount());
        confirmOrderWriter.save(load, confirmation.pointBill(), orderBill);

        return new ConfirmOrderResult(OrderResult.from(order), order.getTotalAmount(), OrderBillStatus.PAID);
    }
}
