package com.loopers.application.order;

import com.loopers.application.payment.PaymentProcessingService;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentRequest;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserService userService;
    private final ProductService productService;
    private final OrderService orderService;
    private final PaymentProcessingService paymentProcessingService;
    private final PgPaymentPort pgPaymentPort;

    public OrderInfo createOrder(Long userId, List<OrderItemCommand> commands) {
        userService.getUser(userId);
        if (commands == null || commands.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 비어있을 수 없습니다.");
        }

        Map<Long, Integer> mergedQuantities = new LinkedHashMap<>();
        for (OrderItemCommand command : commands) {
            mergedQuantities.merge(command.productId(), command.quantity(), Integer::sum);
        }

        List<Long> productIds = List.copyOf(mergedQuantities.keySet());
        Map<Long, ProductModel> products = productService.getActiveProductsByIds(productIds).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        List<OrderItemModel> items = mergedQuantities.entrySet().stream()
            .map(entry -> {
                ProductModel product = products.get(entry.getKey());
                if (product == null) {
                    throw new CoreException(ErrorType.BAD_REQUEST,
                        "[id = " + entry.getKey() + "] 삭제되었거나 존재하지 않는 상품은 주문할 수 없습니다.");
                }
                return new OrderItemModel(product.getId(), entry.getValue(), product.getPrice());
            })
            .toList();

        return OrderInfo.from(orderService.create(userId, items));
    }

    /**
     * PG 3-phase 결제 시작. TX A 완료 후 PG HTTP 호출(트랜잭션 밖), 그 후 TX B로 결과 적용.
     * cardType/cardNo는 LocalPg 테스트 시나리오용이며 실제 PG POST body에는 포함하지 않는다.
     */
    public OrderInfo confirmOrder(Long userId, Long orderId, String paymentAttemptKey, String callbackUrl) {
        userService.getUser(userId);

        PaymentAttemptModel attempt = paymentProcessingService.initiatePayment(userId, orderId, paymentAttemptKey);

        PgPaymentRequest pgRequest = PgPaymentRequest.of(
            attempt.getProviderOrderId(),
            attempt.getAmount(),
            callbackUrl
        );

        PgPaymentResult pgResult;
        try {
            pgResult = pgPaymentPort.pay(pgRequest);
        } catch (Exception e) {
            log.error("PG 호출 예외 발생: orderId={}", orderId, e);
            pgResult = new PgPaymentResult(null, attempt.getProviderOrderId(), attempt.getAmount(), PgPaymentResult.PgStatus.UNKNOWN);
        }

        paymentProcessingService.applyPaymentResult(orderId, pgResult);

        return OrderInfo.from(orderService.getOrderById(orderId));
    }

    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userService.getUser(userId);
        return OrderInfo.from(orderService.getMyOrder(userId, orderId));
    }

    public List<OrderInfo> getMyOrders(Long userId) {
        userService.getUser(userId);
        return orderService.getMyOrders(userId).stream().map(OrderInfo::from).toList();
    }

    public OrderListAdminInfo getOrdersForAdmin(int page, int size) {
        var orders = orderService.getOrdersForAdmin(page, size);
        return new OrderListAdminInfo(
            orders.getContent().stream().map(OrderAdminInfo::from).toList(),
            page, size, orders.getTotalElements()
        );
    }

    public OrderAdminInfo getOrderForAdmin(Long orderId) {
        return OrderAdminInfo.from(orderService.getOrderForAdmin(orderId));
    }
}
