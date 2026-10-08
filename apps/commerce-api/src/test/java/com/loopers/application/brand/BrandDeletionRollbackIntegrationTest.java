package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class BrandDeletionRollbackIntegrationTest {

    @Autowired
    private BrandFacade brandFacade;

    @MockitoSpyBean
    private BrandRepository brandRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void rollsBackProductUpdatesWhenFailureOccursAfterProductUpdate() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product zeroStockProduct = productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
        Product stockedProduct = Product.create(brand.getId(), "Pegasus", 120_000L);
        stockedProduct.changeStockTo(4L);
        stockedProduct = productRepository.save(stockedProduct);

        Brand otherBrand = brandRepository.save(Brand.create("Adidas"));
        Product otherBrandProduct = Product.create(otherBrand.getId(), "Superstar", 90_000L);
        otherBrandProduct.changeStockTo(7L);
        otherBrandProduct = productRepository.save(otherBrandProduct);

        User user = userRepository.save(User.create());
        Order pastOrder = Order.create(user.getId(), List.of(
            OrderItem.create(zeroStockProduct.getId(), zeroStockProduct.getName(), zeroStockProduct.getPrice(), 1)
        ));
        pastOrder.confirm();
        pastOrder = orderRepository.save(pastOrder);

        Timestamp brandUpdatedAtBefore = readUpdatedAt("brands", brand.getId());
        Timestamp zeroStockUpdatedAtBefore = readUpdatedAt("products", zeroStockProduct.getId());
        Timestamp stockedProductUpdatedAtBefore = readUpdatedAt("products", stockedProduct.getId());
        Timestamp otherBrandUpdatedAtBefore = readUpdatedAt("brands", otherBrand.getId());
        Timestamp otherProductUpdatedAtBefore = readUpdatedAt("products", otherBrandProduct.getId());
        AtomicInteger updatedProductCount = new AtomicInteger();

        doAnswer(invocation -> {
            int updatedRows = (int) invocation.callRealMethod();
            updatedProductCount.set(updatedRows);
            throw new IllegalStateException("Injected failure after Product UPDATE");
        }).when(productRepository).softDeleteActiveByBrandId(eq(brand.getId()), any(ZonedDateTime.class));

        assertThatThrownBy(() -> brandFacade.delete(brand.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Injected failure after Product UPDATE");

        entityManager.clear();
        Brand savedBrand = brandRepository.findById(brand.getId()).orElseThrow();
        Product savedZeroStockProduct = productRepository.findById(zeroStockProduct.getId()).orElseThrow();
        Product savedStockedProduct = productRepository.findById(stockedProduct.getId()).orElseThrow();
        Brand savedOtherBrand = brandRepository.findById(otherBrand.getId()).orElseThrow();
        Product savedOtherBrandProduct = productRepository.findById(otherBrandProduct.getId()).orElseThrow();
        Order savedPastOrder = orderRepository.findById(pastOrder.getId()).orElseThrow();

        assertThat(updatedProductCount.get()).isEqualTo(2);
        assertThat(savedBrand.getDeletedAt()).isNull();
        assertThat(savedZeroStockProduct.getDeletedAt()).isNull();
        assertThat(savedZeroStockProduct.getStock().amount()).isZero();
        assertThat(savedStockedProduct.getDeletedAt()).isNull();
        assertThat(savedStockedProduct.getStock().amount()).isEqualTo(4L);
        assertThat(readUpdatedAt("brands", brand.getId())).isEqualTo(brandUpdatedAtBefore);
        assertThat(readUpdatedAt("products", zeroStockProduct.getId())).isEqualTo(zeroStockUpdatedAtBefore);
        assertThat(readUpdatedAt("products", stockedProduct.getId())).isEqualTo(stockedProductUpdatedAtBefore);
        assertThat(savedOtherBrand.getDeletedAt()).isNull();
        assertThat(savedOtherBrand.getName()).isEqualTo("Adidas");
        assertThat(savedOtherBrandProduct.getDeletedAt()).isNull();
        assertThat(savedOtherBrandProduct.getName()).isEqualTo("Superstar");
        assertThat(savedOtherBrandProduct.getPrice()).isEqualTo(90_000L);
        assertThat(savedOtherBrandProduct.getStock().amount()).isEqualTo(7L);
        assertThat(readUpdatedAt("brands", otherBrand.getId())).isEqualTo(otherBrandUpdatedAtBefore);
        assertThat(readUpdatedAt("products", otherBrandProduct.getId())).isEqualTo(otherProductUpdatedAtBefore);
        assertThat(savedPastOrder.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(savedPastOrder.getTotalAmount()).isEqualTo(100_000L);
        assertThat(savedPastOrder.getPaymentAmount()).isEqualTo(100_000L);
        assertThat(savedPastOrder.getPaymentResult().name()).isEqualTo("SUCCESS");
        assertThat(savedPastOrder.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(zeroStockProduct.getId());
            assertThat(item.getProductName()).isEqualTo("Air Max");
            assertThat(item.getUnitPrice()).isEqualTo(100_000L);
            assertThat(item.getQuantity()).isEqualTo(1);
        });
    }

    @Test
    void rollsBackBrandAndProductsWhenFailureOccursAfterBothUpdates() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", 100_000L);
        product.changeStockTo(5L);
        Long productId = productRepository.save(product).getId();
        Timestamp brandUpdatedAtBefore = readUpdatedAt("brands", brand.getId());
        Timestamp productUpdatedAtBefore = readUpdatedAt("products", productId);
        AtomicInteger updatedBrandCount = new AtomicInteger();

        doAnswer(invocation -> {
            updatedBrandCount.set((int) invocation.callRealMethod());
            // 두 UPDATE가 모두 실제 DB에 반영된 상태에서 commit 전에 실패시킨다.
            assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM brands WHERE id = ?", Timestamp.class, brand.getId()
            )).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM products WHERE id = ?", Timestamp.class, productId
            )).isNotNull();
            throw new IllegalStateException("Brand와 Product 갱신 후 테스트 실패");
        }).when(brandRepository).softDeleteActiveById(eq(brand.getId()), any(ZonedDateTime.class));

        assertThatThrownBy(() -> brandFacade.delete(brand.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Brand와 Product 갱신 후 테스트 실패");

        entityManager.clear();
        Brand savedBrand = brandRepository.findById(brand.getId()).orElseThrow();
        Product savedProduct = productRepository.findById(productId).orElseThrow();
        assertThat(updatedBrandCount.get()).isEqualTo(1);
        assertThat(savedBrand.getDeletedAt()).isNull();
        assertThat(savedProduct.getDeletedAt()).isNull();
        assertThat(savedProduct.getStock().amount()).isEqualTo(5L);
        assertThat(readUpdatedAt("brands", brand.getId())).isEqualTo(brandUpdatedAtBefore);
        assertThat(readUpdatedAt("products", productId)).isEqualTo(productUpdatedAtBefore);
    }

    private Timestamp readUpdatedAt(String tableName, Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT updated_at FROM " + tableName + " WHERE id = ?",
            Timestamp.class,
            id
        );
    }
}
