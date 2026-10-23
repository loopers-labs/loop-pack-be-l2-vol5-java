package com.loopers.infrastructure;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 결제 요청 TX A의 주문 전이와 결과 반영 TX B의 주문 종결·재고 반환을 실제 MySQL에서 확인한다.
@SpringBootTest
@Transactional
class PgPaymentOrderRepositoryIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-21T10:00:00+09:00");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Nested
    class RequestPayment {

        @DisplayName("본인의 DRAFT 주문은 1행으로 PAYMENT_PENDING·PG가 되고 품목·금액은 그대로다.")
        @Test
        void startsPaymentOfOwnDraftOrder() {
            Order order = draftOrder();

            assertThat(orderRepository.requestPgPaymentIfDraft(order.getId(), 1L, NOW)).isEqualTo(1);

            Order reloaded = reload(order);
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(reloaded.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(reloaded.getPaymentAmount()).isNull();
            assertThat(reloaded.getTotalAmount()).isEqualTo(200L);
            assertThat(reloaded.getItems()).singleElement().extracting(OrderItem::getQuantity).isEqualTo(2);
        }

        @DisplayName("타인 주문이나 이미 결제를 시작한 주문은 0행이고 값을 바꾸지 않는다.")
        @Test
        void keepsOtherUsersAndStartedOrders() {
            Order order = draftOrder();

            assertThat(orderRepository.requestPgPaymentIfDraft(order.getId(), 2L, NOW)).isZero();
            assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.DRAFT);

            orderRepository.requestPgPaymentIfDraft(order.getId(), 1L, NOW);
            assertThat(orderRepository.requestPgPaymentIfDraft(order.getId(), 1L, NOW)).isZero();
            assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }
    }

    @Nested
    class TerminateOrder {

        @DisplayName("PAYMENT_PENDING 주문의 성공 종결은 1행이고 CONFIRMED·PG·결제액·SUCCESS를 저장한다.")
        @Test
        void completesPendingOrder() {
            Order order = paymentPendingOrder();

            int updated = orderRepository.completePgPaymentIfPending(order.getId(), 200L, NOW);

            assertThat(updated).isEqualTo(1);
            Order reloaded = reload(order);
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reloaded.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(reloaded.getPaymentAmount()).isEqualTo(200L);
            assertThat(reloaded.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
            assertThat(reloaded.getItems()).singleElement().extracting(OrderItem::getQuantity).isEqualTo(2);
        }

        @DisplayName("PAYMENT_PENDING 주문의 실패 종결은 1행이고 PAYMENT_FAILED·FAILED를 저장하며 결제액은 비운다.")
        @Test
        void failsPendingOrder() {
            Order order = paymentPendingOrder();

            int updated = orderRepository.failPgPaymentIfPending(order.getId(), NOW);

            assertThat(updated).isEqualTo(1);
            Order reloaded = reload(order);
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(reloaded.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(reloaded.getPaymentAmount()).isNull();
            assertThat(reloaded.getPaymentResult()).isEqualTo(PaymentResult.FAILED);
        }

        @DisplayName("PAYMENT_PENDING이 아닌 주문은 성공·실패 종결 모두 0행이고 값을 바꾸지 않는다.")
        @Test
        void keepsNonPendingOrders() {
            Order draft = draftOrder();
            Order completed = paymentPendingOrder();
            orderRepository.completePgPaymentIfPending(completed.getId(), 200L, NOW);

            assertThat(orderRepository.completePgPaymentIfPending(draft.getId(), 200L, NOW)).isZero();
            assertThat(orderRepository.failPgPaymentIfPending(draft.getId(), NOW)).isZero();
            assertThat(orderRepository.failPgPaymentIfPending(completed.getId(), NOW)).isZero();
            assertThat(orderRepository.completePgPaymentIfPending(completed.getId(), 999L, NOW)).isZero();

            assertThat(reload(draft).getStatus()).isEqualTo(OrderStatus.DRAFT);
            Order reloaded = reload(completed);
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(reloaded.getPaymentAmount()).isEqualTo(200L);
        }
    }

    @Nested
    class IncreaseStock {

        @DisplayName("재고 반환은 현재 재고에 수량을 더한다.")
        @Test
        void addsQuantityToCurrentStock() {
            Product product = product(8L);

            assertThat(productRepository.increaseStock(product.getId(), 2, NOW)).isEqualTo(1);

            assertThat(reloadStock(product)).isEqualTo(10L);
        }

        @DisplayName("논리 삭제된 상품도 재고를 반환하고 삭제 상태는 유지한다.")
        @Test
        void returnsStockOfDeletedProduct() {
            Product product = product(8L);
            productRepository.softDeleteActiveById(product.getId(), NOW);

            assertThat(productRepository.increaseStock(product.getId(), 2, NOW)).isEqualTo(1);

            entityManager.clear();
            Product reloaded = productRepository.findById(product.getId()).orElseThrow();
            assertThat(reloaded.getStock().amount()).isEqualTo(10L);
            assertThat(reloaded.getDeletedAt()).isNotNull();
        }

        @DisplayName("없는 상품은 0행이다.")
        @Test
        void returnsZeroForMissingProduct() {
            assertThat(productRepository.increaseStock(Long.MAX_VALUE, 2, NOW)).isZero();
        }
    }

    private Order paymentPendingOrder() {
        Order order = draftOrder();
        order.requestPgPayment();
        orderRepository.save(order);
        entityManager.flush();
        entityManager.clear();
        return order;
    }

    private Order draftOrder() {
        Product product = product(8L);
        Order order = orderRepository.save(Order.create(1L, List.of(
            OrderItem.create(product.getId(), "Air Max", 100L, 2)
        )));
        entityManager.flush();
        entityManager.clear();
        return order;
    }

    private Product product(long stock) {
        Brand brand = brandRepository.save(Brand.create("Nike-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 100L);
        product.changeStockTo(stock);
        Product saved = productRepository.save(product);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private Order reload(Order order) {
        entityManager.flush();
        entityManager.clear();
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private long reloadStock(Product product) {
        entityManager.flush();
        entityManager.clear();
        return productRepository.findById(product.getId()).orElseThrow().getStock().amount();
    }
}
