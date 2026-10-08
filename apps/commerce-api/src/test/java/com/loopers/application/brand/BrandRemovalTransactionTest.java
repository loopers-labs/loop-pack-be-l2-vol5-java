package com.loopers.application.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.like.LikeService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BrandRemovalTransactionTest.SqlCaptureConfiguration.class)
class BrandRemovalTransactionTest {
    @MockitoSpyBean private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private OrderService orders;
    @Autowired private LikeService likes;
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

    @Test
    @DisplayName("W3-BRAND-02: 상품 없는 브랜드도 삭제하고 다른 브랜드와 상품은 보존한다.")
    void deletesBrandWithoutProductsAndPreservesOtherRows() {
        Brand target = brands.save(new Brand("빈 브랜드"));
        Brand other = brands.save(new Brand("유지 대상"));
        products.save(new Product(other, "다른 상품", 300, 7));
        var before = jdbc.queryForMap("SELECT * FROM brand WHERE id = ?", target.getId());
        var preserved = preservedState(other.getId());

        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + target.getId(), "admin", null);

        assertSuccessfulDeletion(response, target.getId());
        assertOnlyDeletionFieldsChanged(before, jdbc.queryForMap("SELECT * FROM brand WHERE id = ?", target.getId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT * FROM product WHERE brand_id = ?", target.getId())).isEmpty();
        assertThat(preservedState(other.getId())).isEqualTo(preserved);
    }

    @Test
    @DisplayName("W3-BRAND-02: 없는 브랜드 삭제는 404와 오류 본문을 반환하고 저장 상태를 바꾸지 않는다.")
    void rejectsMissingBrandWithoutChangingRows() {
        Brand other = brands.save(new Brand("유지 대상"));
        products.save(new Product(other, "다른 상품", 300, 7));
        long missingId = other.getId() + 1;
        assertThat(jdbc.queryForList("SELECT * FROM brand WHERE id = ?", missingId)).isEmpty();
        var before = databaseState();

        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + missingId, "admin", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("BRAND_NOT_FOUND");
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo("브랜드를 찾을 수 없습니다.");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("W3-BRAND-02: 미삭제 상품 유무와 무관하게 기존 상품 삭제 이력을 보존하고 재삭제는 변경하지 않는다.")
    void preservesPreviouslyDeletedProductWhenDeletingBrandAndOnRetry(boolean hasActiveProduct) {
        Brand target = brands.save(new Brand("삭제 대상"));
        long deletedId = products.save(new Product(target, "이미 삭제된 상품", 100, 5)).getId();
        // 이전 삭제 시각을 고정해 현재 삭제 시각과 우연히 같아지는 검증을 피한다.
        jdbc.update("UPDATE product SET created_at = '2026-10-01 00:00:00', "
            + "deleted_at = '2026-10-07 01:02:03.123456', updated_at = '2026-10-07 01:02:03.123456' WHERE id = ?",
            deletedId);
        Product active = hasActiveProduct ? products.save(new Product(target, "미삭제 상품", 200, 0)) : null;
        Brand other = brands.save(new Brand("유지 대상"));
        products.save(new Product(other, "다른 상품", 300, 7));
        var brandBefore = jdbc.queryForMap("SELECT * FROM brand WHERE id = ?", target.getId());
        var deletedBefore = jdbc.queryForMap("SELECT * FROM product WHERE id = ?", deletedId);
        var preserved = preservedState(other.getId());

        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + target.getId(), "admin", null);

        assertSuccessfulDeletion(response, target.getId());
        var brandAfter = jdbc.queryForMap("SELECT * FROM brand WHERE id = ?", target.getId());
        assertOnlyDeletionFieldsChanged(brandBefore, brandAfter);
        assertThat(brandAfter.get("deleted_at")).isNotEqualTo(deletedBefore.get("deleted_at"));
        assertThat(jdbc.queryForMap("SELECT * FROM product WHERE id = ?", deletedId)).isEqualTo(deletedBefore);
        if (hasActiveProduct) {
            var activeAfter = jdbc.queryForMap("SELECT * FROM product WHERE id = ?", active.getId());
            assertThat(activeAfter.get("deleted_at")).isEqualTo(brandAfter.get("deleted_at"));
            assertThat(activeAfter.get("updated_at")).isEqualTo(activeAfter.get("deleted_at"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isEqualTo(2L);
        assertThat(preservedState(other.getId())).isEqualTo(preserved);
        var afterFirstDelete = databaseState();

        var retry = AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + target.getId(), "admin", null);

        assertSuccessfulDeletion(retry, target.getId());
        assertThat(databaseState()).isEqualTo(afterFirstDelete);
    }

    @Test
    @DisplayName("W3-BRAND-03: 상품 UPDATE와 브랜드 저장 후 실패하면 전체 변경을 롤백하고 기존 데이터를 보존한다.")
    void rollsBackProductAndBrandUpdatesWhenBrandSaveFailsAfterFlush() {
        Brand target = brands.save(new Brand("삭제 대상"));
        Product first = products.save(new Product(target, "주문한 상품", 100, 5));
        products.save(new Product(target, "품절 상품", 200, 0));
        Brand other = brands.save(new Brand("유지 대상"));
        products.save(new Product(other, "다른 상품", 300, 7));
        jdbc.update("UPDATE user SET point_balance = 1000 WHERE id = 1");
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(first.getId(), 2))).orderId();
        orders.confirm("alice", orderId);
        likes.register("alice", first.getId());
        var before = databaseState();
        var productRowsAfterUpdate = new AtomicReference<List<Map<String, Object>>>();
        var brandRowAfterSave = new AtomicReference<Map<String, Object>>();
        var transactionActiveAtSave = new AtomicBoolean();
        String failureMessage = "test-only: brand save failed after flush";
        sqlCapture.statements.clear();

        doAnswer(invocation -> {
            transactionActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            productRowsAfterUpdate.set(jdbc.queryForList("SELECT * FROM product WHERE brand_id = ? ORDER BY id", target.getId()));
            invocation.callRealMethod();
            brandRowAfterSave.set(jdbc.queryForMap("SELECT * FROM brand WHERE id = ?", target.getId()));
            throw new IllegalStateException(failureMessage);
        }).when(brands).save(any(Brand.class));

        // 테스트 외부 트랜잭션 없이 실제 application 프록시가 커밋·롤백을 책임지게 한다.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + target.getId(), "admin", null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo("일시적인 오류가 발생했습니다.");
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(response.getBody().toString()).doesNotContain(failureMessage);

        // 콜백 안의 assertion이 HTTP 500으로 바뀌어 거짓 통과하지 않도록 수집한 값을 밖에서 검증한다.
        assertThat(transactionActiveAtSave).isTrue();
        assertThat(brandRowAfterSave.get()).isNotNull();
        assertThat(brandRowAfterSave.get().get("deleted_at")).isNotNull();
        assertThat(productRowsAfterUpdate.get()).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("deleted_at")).isEqualTo(brandRowAfterSave.get().get("deleted_at"));
            assertThat(row.get("updated_at")).isEqualTo(row.get("deleted_at"));
        });
        assertThat(sqlCapture.statements).filteredOn(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update product "))
            .hasSize(1);
        assertThat(sqlCapture.statements).filteredOn(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update brand "))
            .hasSize(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(databaseState()).isEqualTo(before);
    }

    private void assertSuccessfulDeletion(ResponseEntity<JsonNode> response, long brandId) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("meta").has("errorCode")).isFalse();
        assertThat(response.getBody().path("meta").has("message")).isFalse();
        assertThat(response.getBody().path("data").path("brandId").asLong()).isEqualTo(brandId);
        assertThat(response.getBody().path("data").path("deleted").asBoolean()).isTrue();
    }

    private void assertOnlyDeletionFieldsChanged(Map<String, Object> before, Map<String, Object> after) {
        assertThat(after.get("deleted_at")).isNotNull();
        assertThat(after.get("updated_at")).isNotNull();
        var expected = new LinkedHashMap<>(before);
        expected.put("deleted_at", after.get("deleted_at"));
        expected.put("updated_at", after.get("updated_at"));
        assertThat(after).isEqualTo(expected);
    }

    private Map<String, List<Map<String, Object>>> databaseState() {
        return Map.of(
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id")
        );
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
