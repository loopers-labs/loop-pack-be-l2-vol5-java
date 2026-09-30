package com.loopers.domain.ordering.policy;

import com.loopers.domain.mall.model.Product;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.pay.model.PointBill;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.LinkedHashMap;
import java.util.Map;

// 주문 확정을 검증 -> 결제 단계 -> 주문 단계 순서로 묶는 순수 도메인 서비스
public final class OrderConfirmationPolicy {

    private OrderConfirmationPolicy() {}

    // 주문 상태 -> 상품별 총수량/삭제/재고 -> 잔액 순서로 검증한 뒤 모두 통과하면 결제 단계, 주문 단계 순으로 변경한다
    public static OrderConfirmation confirm(Order order, Map<Long, Product> productsByProductId, Wallet wallet) {
        order.ensureCanConfirm();

        Map<Long, Integer> quantityByProductId = aggregateQuantities(order.getItems());
        for (Map.Entry<Long, Integer> entry : quantityByProductId.entrySet()) {
            productsByProductId.get(entry.getKey()).ensureCanDecreaseStock(entry.getValue());
        }

        Money paymentAmount = Money.positive(order.getTotalAmount());
        wallet.ensureSufficientBalance(paymentAmount);

        PointBill pointBill = pay(order, wallet, paymentAmount);
        placeOrder(order, productsByProductId, quantityByProductId);

        return new OrderConfirmation(order, productsByProductId, wallet, pointBill);
    }

    // 결제 단계: 지갑에서 포인트를 차감하고 영수증(PointBill)을 만든다
    private static PointBill pay(Order order, Wallet wallet, Money paymentAmount) {
        return wallet.use(paymentAmount, order.getId());
    }

    // 주문 단계: 재고를 차감하고 주문을 확정해 주문 기록을 만든다
    private static void placeOrder(Order order, Map<Long, Product> productsByProductId,
                                    Map<Long, Integer> quantityByProductId) {
        for (Map.Entry<Long, Integer> entry : quantityByProductId.entrySet()) {
            productsByProductId.get(entry.getKey()).decreaseStock(entry.getValue());
        }
        order.confirm();
    }

    // 동일 상품 품목의 수량을 합산하고 초과 시 기존 계산 초과 오류를 사용한다
    private static Map<Long, Integer> aggregateQuantities(Iterable<OrderItem> items) {
        Map<Long, Integer> quantityByProductId = new LinkedHashMap<>();
        for (OrderItem item : items) {
            quantityByProductId.merge(item.getProductId(), item.getQuantity(), OrderConfirmationPolicy::addExact);
        }
        return quantityByProductId;
    }

    private static int addExact(int a, int b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.CALCULATION_OVERFLOW);
        }
    }
}
