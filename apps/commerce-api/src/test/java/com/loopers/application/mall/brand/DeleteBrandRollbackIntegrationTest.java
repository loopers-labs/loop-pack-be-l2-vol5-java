package com.loopers.application.mall.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.loopers.application.ordering.order.ConfirmOrderCommand;
import com.loopers.application.ordering.order.ConfirmOrderUseCase;
import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderItem;
import com.loopers.domain.ordering.order.OrderRepository;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import com.loopers.infrastructure.mall.product.ProductJpaEntity;
import com.loopers.infrastructure.mall.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class DeleteBrandRollbackIntegrationTest {
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private ConfirmOrderUseCase confirmOrderUseCase;
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
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean
    private ProductJpaRepository productJpaRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("두 번째 상품 저장이 실패하면 브랜드·상품 변경 전체를 롤백하고 다른 대상은 영향받지 않는다")
    @Test
    void rollsBackEverything_whenSecondProductSaveFails() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product productA = productRepository.save(Product.create(brand.getId(), "상품A", null, 1_000L, 5));
        Product productB = productRepository.save(Product.create(brand.getId(), "상품B", null, 1_000L, 5));

        Brand otherBrand = brandRepository.save(Brand.create("다른 브랜드", null));
        Product otherProduct = productRepository.save(Product.create(otherBrand.getId(), "다른 상품", null, 1_000L, 5));

        long pastOrderId = createConfirmedOrder();

        doThrow(new IllegalStateException("forced failure"))
            .when(productJpaRepository).saveAndFlush(argThat(
                (ProductJpaEntity entity) -> entity.getId().equals(productB.getId())));

        try {
            assertThatThrownBy(() -> deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId())))
                .isInstanceOf(RuntimeException.class);

            assertAll(
                () -> assertThat(brandRepository.findById(brand.getId()).orElseThrow().isDeleted()).isFalse(),
                () -> assertThat(productRepository.findById(productA.getId()).orElseThrow().isDeleted()).isFalse(),
                () -> assertThat(productRepository.findById(productB.getId()).orElseThrow().isDeleted()).isFalse(),
                () -> assertThat(brandRepository.findById(otherBrand.getId()).orElseThrow().isDeleted()).isFalse(),
                () -> assertThat(productRepository.findById(otherProduct.getId()).orElseThrow().isDeleted()).isFalse(),
                () -> assertThat(orderStatus(pastOrderId)).isEqualTo("CONFIRMED"),
                () -> assertThat(walletRepository.findByUserId(2L).orElseThrow().getBalance()).isEqualTo(8_000L)
            );
        } finally {
            reset(productJpaRepository);
        }
    }

    private long createConfirmedOrder() {
        Brand brand = brandRepository.save(Brand.create("과거 주문 브랜드", null));
        Product product = productRepository.save(Product.create(brand.getId(), "과거 주문 상품", null, 1_000L, 5));
        userRepository.save(User.create(2L));
        Wallet wallet = walletRepository.save(Wallet.zero(2L));
        wallet.charge(Money.positive(10_000L));
        walletRepository.save(wallet);
        OrderItem item = OrderItem.create(product.getId(), "과거 주문 상품", 1_000L, 2);
        Order order = orderRepository.save(Order.create(2L, List.of(item)));

        confirmOrderUseCase.execute(new ConfirmOrderCommand(order.getId()));

        return order.getId();
    }

    private String orderStatus(long orderId) {
        return jdbcClient.sql("SELECT status FROM orders WHERE id = :orderId")
            .param("orderId", orderId)
            .query(String.class)
            .single();
    }
}
