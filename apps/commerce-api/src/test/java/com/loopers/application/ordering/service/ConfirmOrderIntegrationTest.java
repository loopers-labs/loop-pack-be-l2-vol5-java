package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.support.test.IntegrationTest;
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ConfirmOrderIntegrationTest {
    @Autowired
    private ConfirmOrderUseCase confirmOrderUseCase;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderConfirmProbe probe;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 확정")
    @Nested
    class Confirm {
        @DisplayName("재고·잔액을 차감하고 USE·PAID 기록을 남긴 뒤 CONFIRMED로 저장한다")
        @Test
        void confirmsOrder_withStockPointAndRecords() {
            long productId = createProduct(5);
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            Order order = createOrder(1L, productId, 2, 1_000L);

            ConfirmOrderResult result = confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId()));

            Product reloadedProduct = productRepository.findById(productId).orElseThrow();
            Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.order().status()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(result.paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(reloadedProduct.getStock()).isEqualTo(3),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(8_000L),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isEqualTo(1L),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isEqualTo(1L),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("CONFIRMED"),
                // 재고 외 무관한 상품 값은 잠금 조회·저장 이후에도 그대로 보존돼야 한다
                () -> assertThat(reloadedProduct.getName()).isEqualTo("상품"),
                () -> assertThat(reloadedProduct.getPrice()).isEqualTo(1_000L),
                () -> assertThat(reloadedProduct.isDeleted()).isFalse(),
                // 주문 당시 품목 스냅샷도 그대로 보존돼야 한다
                () -> assertThat(reloadedOrder.getItems())
                    .extracting(OrderItem::getProductId, OrderItem::getProductName, OrderItem::getUnitPrice,
                        OrderItem::getQuantity)
                    .containsExactly(Tuple.tuple(productId, "상품", 1_000L, 2))
            );
        }

        @DisplayName("두 번째 품목의 재고가 부족하면 전체를 롤백한다")
        @Test
        void rollsBackEverything_whenSecondItemStockIsInsufficient() {
            long sufficientProductId = createProduct(5);
            long insufficientProductId = createProduct(1);
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            List<OrderItem> items = List.of(
                OrderItem.create(sufficientProductId, "상품1", 1_000L, 2),
                OrderItem.create(insufficientProductId, "상품2", 1_000L, 2)
            );
            Order order = orderRepository.save(Order.create(1L, items));

            assertThatThrownBy(() -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);

            assertAll(
                () -> assertThat(productRepository.findById(sufficientProductId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(productRepository.findById(insufficientProductId).orElseThrow().getStock()).isEqualTo(1),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("DRAFT"),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isZero()
            );
        }

        @DisplayName("포인트가 부족하면 재고 차감을 포함해 전체를 롤백한다")
        @Test
        void rollsBackEverything_whenPointIsInsufficient() {
            long productId = createProduct(5);
            userRepository.save(User.create(1L));
            walletRepository.save(Wallet.zero(1L));
            Order order = createOrder(1L, productId, 2, 1_000L);

            assertThatThrownBy(() -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);

            assertAll(
                () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isZero(),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("DRAFT"),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isZero()
            );
        }

        @DisplayName("브랜드 일괄 삭제로 상품이 삭제되면 DRAFT 확정을 거절하고 재고·잔액·기록을 보존한다")
        @Test
        void rejectsConfirm_whenProductDeletedViaBrandBulkDelete() {
            Brand brand = brandRepository.save(Brand.create("브랜드", null));
            long productId = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5)).getId();
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            Order order = createOrder(1L, productId, 2, 1_000L);

            deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId()));

            assertThatThrownBy(() -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);

            assertAll(
                () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("DRAFT"),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isZero()
            );
        }

        @DisplayName("이미 확정된 주문을 다시 확정하면 추가 차감·기록 없이 거절한다")
        @Test
        void rejectsReconfirm_withoutAdditionalChangesOrRecords() {
            long productId = createProduct(5);
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            Order order = createOrder(1L, productId, 2, 1_000L);
            confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId()));

            assertThatThrownBy(() -> confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId())))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);

            assertAll(
                () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(8_000L),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isEqualTo(1L),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isEqualTo(1L)
            );
        }
    }

    private long createProduct(int stock) {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        return productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, stock)).getId();
    }

    private Order createOrder(long userId, long productId, int quantity, long unitPrice) {
        OrderItem item = OrderItem.create(productId, "상품", unitPrice, quantity);
        return orderRepository.save(Order.create(userId, List.of(item)));
    }
}
