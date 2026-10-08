package com.loopers.application.brand;

import com.loopers.application.order.OrderService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductException;
import com.loopers.domain.user.UserRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BrandRemovalTransactionTest.SqlCaptureConfiguration.class)
class BrandRemovalTransactionTest {
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private OrderService orders;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private AdminBrandService removal;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlCapture sqlCapture;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics().setStatisticsEnabled(false);
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("W3-BRAND-01: 브랜드와 재고 0을 포함한 연결 상품을 함께 삭제하고 다른 대상과 확정 주문을 보존한다.")
    void deletesBrandAndProductsWhilePreservingOtherDataAndConfirmedOrder() throws Exception {
        Brand target = brands.save(new Brand("삭제 대상"));
        Product first = products.save(new Product(target, "주문한 상품", 100, 5));
        products.save(new Product(target, "품절 상품", 200, 0));
        Brand other = brands.save(new Brand("유지 대상"));
        products.save(new Product(other, "다른 브랜드 상품", 300, 7));
        jdbc.update("UPDATE user SET point_balance = 1000 WHERE id = 1");
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(first.getId(), 2))).orderId();
        orders.confirm("alice", orderId);
        var preserved = preservedState(other.getId());
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        sqlCapture.statements.clear();

        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + target.getId(), "admin", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data").path("brandId").asLong()).isEqualTo(target.getId());
        assertThat(response.getBody().path("data").path("deleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE id = ? AND deleted_at IS NOT NULL",
            Long.class, target.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN brand b ON b.id = p.brand_id "
            + "WHERE b.id = ? AND p.deleted_at = b.deleted_at AND p.updated_at = p.deleted_at",
            Long.class, target.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(3L);
        assertThat(preservedState(other.getId())).isEqualTo(preserved);
        assertThat(statistics.getEntityStatistics(Product.class.getName()).getLoadCount()).isZero();
        assertThat(statistics.getEntityStatistics(Product.class.getName()).getUpdateCount()).isZero();
        assertThat(sqlCapture.statements).filteredOn(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update product "))
            .hasSize(1);
    }

    @Test
    @DisplayName("W3-BRAND-01 기술 검증: 외부 트랜잭션의 상품 객체를 동기화하고 다른 관리 객체의 변경을 보존한다.")
    void refreshesPreviouslyLoadedProductWithoutClearingOtherManagedEntities() {
        Brand target = brands.save(new Brand("삭제 대상"));
        long productId = products.save(new Product(target, "이전 이름", 100, 5)).getId();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status -> {
            var user = users.lockById(1).orElseThrow();
            Brand brand = brands.lockById(target.getId()).orElseThrow();
            Product product = products.lockById(productId).orElseThrow();
            user.charge(100);
            product.update("변경한 이름", 200);

            removal.delete(UserRole.ADMIN, target.getId());

            assertThat(entityManager.contains(user)).isTrue();
            assertThat(entityManager.contains(brand)).isTrue();
            assertThat(entityManager.contains(product)).isTrue();
            assertThat(product.isDeleted()).isTrue();
            assertThat(product.getName()).isEqualTo("변경한 이름");
            assertThat(product.getPrice()).isEqualTo(200);
            assertThat(product.getUpdatedAt()).isEqualTo(product.getDeletedAt());
            assertThatThrownBy(() -> product.changeStockQuantityTo(9))
                .isInstanceOfSatisfying(ProductException.class,
                    error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.DELETED_PRODUCT));
            user.charge(50);
        });

        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id = 1", Long.class)).isEqualTo(150L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id = ? AND deleted_at IS NOT NULL "
            + "AND name = '변경한 이름' AND price = 200 AND stock_quantity = 5", Long.class, productId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE id = ? AND deleted_at IS NOT NULL",
            Long.class, target.getId())).isEqualTo(1L);
    }

    private Map<String, List<Map<String, Object>>> preservedState(long otherBrandId) {
        return Map.of(
            "otherBrand", jdbc.queryForList("SELECT * FROM brand WHERE id = ?", otherBrandId),
            "otherProducts", jdbc.queryForList("SELECT * FROM product WHERE brand_id = ? ORDER BY id", otherBrandId),
            "productFields", jdbc.queryForList("SELECT id, brand_id, name, price, stock_quantity, created_at FROM product ORDER BY id"),
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id")
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        SqlCapture sqlCapture() {
            return new SqlCapture();
        }

        @Bean
        HibernatePropertiesCustomizer captureSql(SqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }

    static class SqlCapture implements StatementInspector {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }
    }
}
