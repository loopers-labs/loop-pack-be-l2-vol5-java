package com.loopers.application.order;

import com.loopers.application.user.AdminAuthorization;
import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentRecoveryException;
import com.loopers.domain.user.UserRepository;
import com.loopers.domain.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Uses the existing query transaction isolation; never locks, verifies card input or calls the PG. */
@Service
@Transactional(readOnly = true)
public class OrderPaymentQueryService {
    private final UserResolver userResolver;
    private final UserRepository users;
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;

    public OrderPaymentQueryService(UserResolver userResolver, UserRepository users, OrderRepository orders,
                                    PaymentAttemptRepository attempts) {
        this.userResolver = userResolver;
        this.users = users;
        this.orders = orders;
        this.attempts = attempts;
    }

    public OrderPaymentInfo getDetail(String requester, long orderId) {
        long userId = existingUserId(requester);
        Order order = orders.findById(orderId).orElseThrow(this::orderNotFound);
        order.requireOwner(userId);
        return OrderPaymentInfo.from(order, attemptsFor(List.of(order)).get(orderId));
    }

    public PageResult<OrderPaymentInfo> getPage(String requester, int page, int size) {
        long userId = existingUserId(requester);
        PageResult<Order> result = orders.findPage(userId, null, page, size);
        Map<Long, PaymentAttempt> payments = attemptsFor(result.items());
        return result.map(order -> OrderPaymentInfo.from(order, payments.get(order.getId())));
    }

    public OrderPaymentAdminInfo adminDetail(UserRole requester, long orderId) {
        AdminAuthorization.requireAdmin(requester);
        Order order = orders.findById(orderId).orElseThrow(this::orderNotFound);
        return adminInfo(order, attemptsFor(List.of(order)).get(orderId));
    }

    /** End this read-only TX before locking the order, so an earlier managed attempt cannot mask a newer commit. */
    public CallbackTarget callbackTarget(String providerAccount, String providerOrderId) {
        PaymentAttempt attempt = attempts.findByProviderOrderId(providerAccount, providerOrderId)
            .orElseThrow(() -> new PaymentRecoveryException(PaymentRecoveryException.Reason.PAYMENT_ATTEMPT_NOT_FOUND));
        return new CallbackTarget(attempt.getOrderId(), attempt.getAttemptKey());
    }

    public record CallbackTarget(long orderId, UUID attemptKey) {
    }

    public PageResult<OrderPaymentAdminInfo> adminPage(UserRole requester, String userId, OrderStatus status,
                                                     int page, int size) {
        AdminAuthorization.requireAdmin(requester);
        Long buyerId = null;
        if (userId != null) {
            try {
                buyerId = userResolver.resolve(userId).userId();
            } catch (UserResolutionException exception) {
                if (exception.getReason() == UserResolutionException.Reason.USER_NOT_FOUND) {
                    return PageResult.of(List.of(), page, size, 0);
                }
                throw exception;
            }
        }
        PageResult<Order> result = orders.findPage(buyerId, status, page, size);
        Map<Long, PaymentAttempt> payments = attemptsFor(result.items());
        return result.map(order -> adminInfo(order, payments.get(order.getId())));
    }

    private Map<Long, PaymentAttempt> attemptsFor(List<Order> selectedOrders) {
        if (selectedOrders.isEmpty()) {
            return Map.of();
        }
        return attempts.findByOrderIds(selectedOrders.stream().map(Order::getId).toList()).stream()
            .collect(Collectors.toMap(PaymentAttempt::getOrderId, Function.identity()));
    }

    private OrderPaymentAdminInfo adminInfo(Order order, PaymentAttempt attempt) {
        String externalId = userResolver.findExternalId(order.getUserId())
            .orElseThrow(() -> new IllegalStateException("Stored buyer has no external identity"));
        return new OrderPaymentAdminInfo(OrderPaymentInfo.from(order, attempt), externalId);
    }

    private long existingUserId(String requester) {
        long userId = userResolver.resolve(requester).userId();
        users.findById(userId).orElseThrow(UserResolutionException::userNotFound);
        return userId;
    }

    private OrderException orderNotFound() {
        return new OrderException(OrderException.Reason.ORDER_NOT_FOUND);
    }
}
