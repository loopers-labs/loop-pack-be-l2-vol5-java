package com.loopers.application.order;

import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderServiceIntegrationTest {
    @Autowired
    private OrderService service;
    @Autowired
    private BrandRepository brands;
    @Autowired
    private ProductRepository products;
    @Autowired
    private UserRepository users;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private DatabaseCleanUp cleanUp;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    void persistsDraftWithCombinedSnapshotsWithoutDeductingStockOrPoints() {
        Product product = products.save(new Product(brands.save(new Brand("브랜드")), "상품", 100, 5));
        OrderInfo order = service.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2),
            new OrderQuantities.Item(product.getId(), 3)));

        assertThat(order).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.totalAmount()).isEqualTo(500);
        assertThat(order.items()).containsExactly(new OrderInfo.ItemInfo(product.getId(), "상품", 100, 5, 500));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(1);
        assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(users.findById(1).orElseThrow().getBalance()).isZero();
    }

    @Test
    void rollsBackAllFlushedOrderStockAndBalanceChangesWhenCallerFails() {
        Product first = product("첫 상품");
        Product second = product("두 번째 상품");
        jdbc.update("UPDATE user SET point_balance = 1000 WHERE id = 1");
        long orderId = service.create("alice", List.of(new OrderQuantities.Item(first.getId(), 2),
            new OrderQuantities.Item(second.getId(), 3))).orderId();
        var before = storedState();
        IllegalStateException failure = new IllegalStateException("after flush");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(service.confirm("alice", orderId).status()).isEqualTo(OrderStatus.CONFIRMED);
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(500);
            throw failure;
        })).isSameAs(failure);

        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void rollsBackNewOrderAndItemsWhenCallerFailsAfterFlush() {
        Product product = product("상품");
        var before = storedState();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.create("alice", List.of(new OrderQuantities.Item(product.getId(), 1)));
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(1);
            throw new IllegalStateException("after insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(storedState()).isEqualTo(before);
    }

    private Product product(String name) {
        return products.save(new Product(brands.save(new Brand("브랜드")), name, 100, 5));
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"));
    }
}
