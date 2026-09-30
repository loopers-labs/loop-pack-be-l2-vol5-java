package com.loopers.infrastructure.dao.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.loopers.application.ordering.dao.ConfirmOrderLoad;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PointBill;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.PointBillRepository;
import com.loopers.domain.pay.repository.WalletRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class JpaConfirmOrderWriterTest {

    @DisplayName("조회")
    @Nested
    class Load {
        @DisplayName("주문 -> 지갑 -> 상품 ID 오름차순(중복 제거) 순서로 잠금 조회한다")
        @Test
        void locksInOrder_orderThenWalletThenProductsAscending_deduplicated() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            WalletRepository walletRepository = mock(WalletRepository.class);
            PointBillRepository pointBillRepository = mock(PointBillRepository.class);
            Order order = Order.restore(1L, 1L, OrderStatus.DRAFT, List.of(
                OrderItem.restore(30L, "상품C", 1_000L, 1, 1_000L),
                OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L),
                OrderItem.restore(20L, "상품B", 1_000L, 1, 1_000L),
                OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L)
            ), 4_000L, Instant.now(), null);
            given(orderRepository.findByIdForUpdate(1L)).willReturn(java.util.Optional.of(order));
            given(walletRepository.findByUserIdForUpdate(1L)).willReturn(java.util.Optional.of(Wallet.restore(1L, 10_000L)));
            given(productRepository.findByIdForUpdate(anyLong())).willAnswer(
                invocation -> java.util.Optional.of(product(invocation.getArgument(0))));
            JpaConfirmOrderWriter writer = new JpaConfirmOrderWriter(
                orderRepository, productRepository, walletRepository, pointBillRepository);

            // act
            ConfirmOrderLoad load = writer.load(1L);

            // assert
            InOrder callOrder = inOrder(orderRepository, walletRepository, productRepository);
            callOrder.verify(orderRepository).findByIdForUpdate(1L);
            callOrder.verify(walletRepository).findByUserIdForUpdate(1L);
            callOrder.verify(productRepository).findByIdForUpdate(10L);
            callOrder.verify(productRepository).findByIdForUpdate(20L);
            callOrder.verify(productRepository).findByIdForUpdate(30L);
            assertThat(load.productsByProductId()).hasSize(3);
        }
    }

    @DisplayName("저장")
    @Nested
    class Save {
        @DisplayName("상품 재고 -> 지갑 잔액 -> 사용 기록 -> 주문(기록은 cascade) 순서로 저장한다")
        @Test
        void savesInOrder() {
            // arrange
            OrderRepository orderRepository = mock(OrderRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            WalletRepository walletRepository = mock(WalletRepository.class);
            PointBillRepository pointBillRepository = mock(PointBillRepository.class);
            JpaConfirmOrderWriter writer = new JpaConfirmOrderWriter(
                orderRepository, productRepository, walletRepository, pointBillRepository);
            OrderRecord record = OrderRecord.restore(1L, 1L, 1_000L, OrderRecordStatus.PAID, Instant.now());
            Order order = Order.restore(1L, 1L, OrderStatus.CONFIRMED,
                List.of(OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L)), 1_000L, Instant.now(), record);
            Product product = product(10L);
            Wallet wallet = Wallet.restore(1L, 9_000L);
            ConfirmOrderLoad load = new ConfirmOrderLoad(order, Map.of(10L, product), wallet);
            PointBill pointBill = PointBill.use(1L, 1L, 1_000L);

            // act
            writer.save(load, pointBill);

            // assert
            InOrder callOrder = inOrder(productRepository, walletRepository, pointBillRepository, orderRepository);
            callOrder.verify(productRepository).save(product);
            callOrder.verify(walletRepository).save(wallet);
            callOrder.verify(pointBillRepository).save(pointBill);
            callOrder.verify(orderRepository).save(order);
        }
    }

    private Product product(long id) {
        return Product.restore(id, 1L, "상품", null, 1_000L, 5, false, Instant.now());
    }
}
