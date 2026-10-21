package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentRecord;
import java.util.List;
import java.util.Optional;

public record OrderResult(long id, long userId, List<Item> items, long total, String status, long paidAmount,
    String paymentResult, String paymentMethod, String providerStatus) {
    public record Item(long productId, int quantity, long unitPrice) { }

    static OrderResult from(Order order, Optional<PaymentRecord> payment) {
        List<Item> items = order.getItems().stream()
            .map(i -> new Item(i.productId().value(), i.quantity().value(), i.unitPrice().value())).toList();
        long paidAmount = payment.filter(found -> found.getResult() == PaymentRecord.Result.SUCCESS)
            .map(found -> found.getAmount().value())
            .orElse(0L);
        String paymentResult = payment.map(found -> found.getResult().name()).orElse("NOT_PAID");
        String paymentMethod = payment.map(found -> found.getMethod().name()).orElse(null);
        return new OrderResult(order.getId(), order.getUserId(), items, order.getTotal().value(), order.getStatus().name(),
            paidAmount, paymentResult, paymentMethod,
            payment.map(PaymentRecord::getProviderStatus).map(Enum::name).orElse(null));
    }
}
