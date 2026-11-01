package com.loopers.order.application;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderRepository;
import com.loopers.order.application.event.OrderConfirmedEvent;
import com.loopers.order.domain.Payment;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.CancellationResult;
import com.loopers.order.domain.PaymentGateway.DispatchRejected;
import com.loopers.order.domain.PaymentMethod;
import com.loopers.order.domain.PaymentStatus;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.Stock;
import com.loopers.product.domain.StockRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OrderPaymentTransactionService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final StockRepository stockRepository;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public OrderPaymentTransactionService(
        OrderRepository orderRepository,
        ProductRepository productRepository,
        StockRepository stockRepository,
        EntityManager entityManager,
        ApplicationEventPublisher eventPublisher
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.stockRepository = stockRepository;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DispatchPlan prepare(Long buyerId, Long orderId, String paymentKey, String paymentAttemptKey) {
        Order order = findOwnedOrder(buyerId, orderId);
        Order existingAttempt = orderRepository.findByPaymentAttemptKey(paymentAttemptKey).orElse(null);
        if (existingAttempt != null) {
            if (!existingAttempt.isOwnedBy(buyerId) || !existingAttempt.getId().equals(orderId)) {
                throw new CoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
            }
            Payment payment = existingAttempt.getPayment();
            if (payment.status() == PaymentStatus.SUCCESS || payment.status() == PaymentStatus.FAILED
                || payment.status() == PaymentStatus.UNKNOWN || payment.status() == PaymentStatus.CANCELED
                || payment.status() == PaymentStatus.CANCEL_PROCESSING) {
                throw new CoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
            }
            return new DispatchPlan(existingAttempt, null);
        }

        if (order.getPayment() != null) {
            throw new CoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        List<Product> products = order.getItems().stream()
            .map(OrderItem::productId)
            .distinct()
            .sorted()
            .map(productRepository::findForOrder)
            .flatMap(Optional::stream)
            .toList();
        if (products.size() != order.getItems().stream().map(OrderItem::productId).distinct().count()
            || products.stream().anyMatch(Product::isDeleted)) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }
        List<Stock> stocks = products.stream()
            .map(product -> stockRepository.findForOrderByProductId(product.getId())
                .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_AVAILABLE)))
            .toList();
        order.reserveStock();
        for (Stock stock : stocks) {
            int quantity = quantityFor(order, stock.getProductId());
            stock.changeQuantity(stock.decrease(quantity).quantity());
            stockRepository.save(stock);
        }

        String providerOrderId = "LP-ORD-%010d".formatted(orderId);
        Payment payment = new Payment(
            PaymentMethod.PG, order.getTotalAmount(), paymentKey, paymentAttemptKey, providerOrderId);
        payment.markDispatchStarted(ZonedDateTime.now());
        order.setPayment(payment);
        orderRepository.save(order);
        entityManager.flush();

        return new DispatchPlan(order, new PaymentGateway.ApprovalRequest(
            paymentKey, providerOrderId, order.getTotalAmount(), paymentAttemptKey));
    }

    @Transactional
    public Order recordResult(Long orderId, PaymentGateway.ApprovalResult result) {
        Order order = orderRepository.findForConfirmById(orderId)
            .orElseThrow(() -> new CoreException(ErrorCode.ORDER_NOT_FOUND));
        Payment payment = order.getPayment();
        if (payment == null) {
            throw new CoreException(ErrorCode.INTERNAL_ERROR);
        }
        payment.recordResult(result);

        if (result instanceof PaymentGateway.Approved approved) {
            order.consumeStockReservation();
            order.confirm(approved.amount(), approved.paidAt().toZonedDateTime(), ZonedDateTime.now());
        } else if (result instanceof PaymentGateway.ProviderError || result instanceof DispatchRejected) {
            restoreReservedStock(order);
            order.releaseStockReservation();
        }

        orderRepository.save(order);
        entityManager.flush();
        if (result instanceof PaymentGateway.Approved) {
            entityManager.refresh(order);
            eventPublisher.publishEvent(OrderConfirmedEvent.from(order));
        }
        return order;
    }

    @Transactional
    public Order recordCancellationResultAndReleaseReservation(Long orderId, CancellationResult result) {
        Order order = orderRepository.findForConfirmById(orderId)
            .orElseThrow(() -> new CoreException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getPayment() == null || order.getPayment().status() != PaymentStatus.UNKNOWN) {
            throw new CoreException(ErrorCode.INTERNAL_ERROR);
        }
        restoreReservedStock(order);
        order.releaseStockReservation();
        order.getPayment().recordCancellationResult(result);
        orderRepository.save(order);
        entityManager.flush();
        return order;
    }

    private Order findOwnedOrder(Long buyerId, Long orderId) {
        Order order = orderRepository.findForConfirmById(orderId)
            .orElseThrow(() -> new CoreException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.isOwnedBy(buyerId)) {
            throw new CoreException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private void restoreReservedStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Stock stock = stockRepository.findForOrderByProductId(item.productId())
                .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_AVAILABLE));
            stock.changeQuantity(Math.addExact(stock.quantity(), item.quantity()));
            stockRepository.save(stock);
        }
    }

    private int quantityFor(Order order, Long productId) {
        return order.getItems().stream()
            .filter(item -> item.productId().equals(productId))
            .mapToInt(OrderItem::quantity)
            .sum();
    }

    public record DispatchPlan(Order order, PaymentGateway.ApprovalRequest request) {
        public boolean shouldDispatch() {
            return request != null;
        }
    }
}
