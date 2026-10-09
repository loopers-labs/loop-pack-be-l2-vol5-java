package com.loopers.application.ordering.facade;

import com.loopers.application.mall.service.ProductService;
import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.result.OrderResult;
import com.loopers.application.ordering.service.OrderService;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.pay.service.WalletService;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.pay.model.Wallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 주문·지갑·상품 서비스를 순서대로 호출해 주문 확정을 한 트랜잭션으로 조율하는 파사드
public class ConfirmOrderFacade implements ConfirmOrderUseCase {
    private final OrderService orderService;
    private final WalletService walletService;
    private final ProductService productService;

    // 잠금 순서(주문 -> 지갑 -> 상품 id 오름차순)와 오류 우선순위(주문 상태 -> 상품 -> 잔액)를 호출 순서로 지킨다
    @Override
    @Transactional
    public ConfirmOrderResult execute(ConfirmOrderCommand command) {
        Order order = orderService.lockForConfirm(command.orderId());
        Wallet wallet = walletService.lockByUserId(order.getUserId());
        productService.decreaseStocks(order.quantitiesByProductId());
        walletService.pay(wallet, order);
        Order confirmed = orderService.confirm(order);

        OrderRecord record = confirmed.getRecord().orElseThrow();
        return new ConfirmOrderResult(OrderResult.from(confirmed), record.getAmount(), record.getStatus());
    }
}
