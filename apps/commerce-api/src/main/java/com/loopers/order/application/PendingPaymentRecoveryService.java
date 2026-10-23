package com.loopers.order.application;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderRepository;
import com.loopers.order.domain.Payment;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalResult;
import com.loopers.order.domain.PaymentGateway.Unknown;
import com.loopers.order.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PendingPaymentRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingPaymentRecoveryService.class);
    private static final int PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderPaymentTransactionService transactionService;
    private final PaymentGateway paymentGateway;

    public PendingPaymentRecoveryService(
        OrderRepository orderRepository,
        OrderPaymentTransactionService transactionService,
        PaymentGateway paymentGateway
    ) {
        this.orderRepository = orderRepository;
        this.transactionService = transactionService;
        this.paymentGateway = paymentGateway;
    }

    public void recoverPendingPayments() {
        long afterOrderId = 0;
        while (true) {
            List<Order> unresolvedOrders = orderRepository.findUnresolvedPaymentOrders(afterOrderId, PAGE_SIZE);
            if (unresolvedOrders.isEmpty()) {
                return;
            }
            for (Order order : unresolvedOrders) {
                recover(order);
            }
            afterOrderId = unresolvedOrders.getLast().getId();
        }
    }

    private void recover(Order order) {
        Payment payment = order.getPayment();
        if (payment == null || (payment.status() != PaymentStatus.PENDING
            && payment.status() != PaymentStatus.UNKNOWN)) {
            return;
        }

        ApprovalResult result;
        try {
            result = paymentGateway.findByPaymentKey(payment.paymentKey());
        } catch (RuntimeException exception) {
            LOGGER.warn("PG payment lookup failed during startup recovery: paymentKey={}",
                payment.paymentKey(), exception);
            return;
        }
        if (result == null || result instanceof Unknown) {
            LOGGER.info("Pending payment remains unresolved after startup lookup: orderId={}, paymentKey={}",
                order.getId(), payment.paymentKey());
            return;
        }

        transactionService.recordResult(order.getId(), result);
        LOGGER.info("Recovered pending payment: orderId={}, paymentKey={}, result={}",
            order.getId(), payment.paymentKey(), result.getClass().getSimpleName());
    }
}
