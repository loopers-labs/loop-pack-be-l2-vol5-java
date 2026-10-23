package com.loopers.application.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.List;

/**
 * 트랜잭션 안에서 구성한 주문 결과. Controller 가 지연 로딩에 의존하지 않도록 값으로 복사한다.
 */
public record OrderInfo(
    Long id,
    Long userId,
    String status,
    Long totalAmount,
    Long pointAmount,
    Long paidAmount,
    List<Item> items,
    PaymentInfo payment
) {
    /**
     * 주문의 결제 정보(설계 2.7 주문 응답 조립). DRAFT 는 null, 과거 POINT 는 method·status 만, PG 는 저장 시도에서 복사한다.
     * 성공 금액은 Order 의 paidAmount 이며 시도의 amount 와 섞지 않는다.
     */
    public record PaymentInfo(String method, String status, String paymentAttemptKey, Long amount,
                              String verificationStatus) {

        static PaymentInfo of(OrderModel order, PaymentModel attempt) {
            if (order.getPaymentMethod() == null) {
                return null;
            }
            if (order.getPaymentMethod() == PaymentMethod.POINT) {
                return new PaymentInfo(PaymentMethod.POINT.name(), "SUCCESS", null, null, null);
            }
            if (attempt == null) {
                throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 결제 주문에 결제 시도가 없습니다. [orderId = " + order.getId() + "]");
            }
            return new PaymentInfo(PaymentMethod.PG.name(), attempt.getStatus().name(), attempt.getAttemptKey(),
                attempt.getAmount().toWon(), attempt.getVerificationStatus().name());
        }
    }

    public record Item(Long productId, Long quantity, Long unitPrice, Long amount) {
        public static Item from(OrderItemModel item) {
            return new Item(
                item.getProductId(),
                item.getQuantity(),
                item.getUnitPrice().toWon(),
                item.calculateAmount().toWon()
            );
        }
    }

    /** 결제 시도가 없는 주문(DRAFT·과거 POINT)용. PG 주문은 {@link #from(OrderModel, PaymentModel)} 를 쓴다. */
    public static OrderInfo from(OrderModel order) {
        return from(order, null);
    }

    public static OrderInfo from(OrderModel order, PaymentModel attempt) {
        Money paidAmount = order.getPaidAmount();
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus().name(),
            order.getTotalAmount().toWon(),
            order.getPointAmount(),
            paidAmount != null ? paidAmount.toWon() : null,
            order.getItems().stream().map(Item::from).toList(),
            PaymentInfo.of(order, attempt)
        );
    }
}
