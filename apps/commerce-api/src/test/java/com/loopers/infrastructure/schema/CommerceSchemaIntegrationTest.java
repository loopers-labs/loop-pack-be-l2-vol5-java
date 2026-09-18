package com.loopers.infrastructure.schema;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandNameConverter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class CommerceSchemaIntegrationTest {

    private static final List<String> TABLES = List.of("brand", "user", "product", "like", "order", "order_item");
    private static final List<String> SCRIPTS = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("commerce_schema_test");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void appliesOnlyManualScriptsToAnEmptyIsolatedDatabase() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // 이 클래스 전용 컨테이너에서만 자식 테이블부터 정리한다. 운영 SQL에는 DROP이 없다.
        for (String table : List.of("order_item", "like", "order", "product", "user", "brand")) {
            jdbc.execute("DROP TABLE IF EXISTS `" + table + "`");
        }
        applySchema();
    }

    @Test
    void validatesAllJpaMappingsAndRoundTripsTheWholeGraphWithoutAutomaticDdl() {
        Brand brand = new Brand("한".repeat(100));
        User user = new User(1);
        user.charge(Long.MAX_VALUE);
        Product product = new Product(brand, "😀".repeat(100), Long.MAX_VALUE, Integer.MAX_VALUE);
        Order order = Order.create(user, List.of(new OrderItem(product, 1)));
        ZonedDateTime confirmedAt = ZonedDateTime.parse("2026-09-18T12:34:56.123456+09:00");
        order.confirm(confirmedAt);
        ProductLike like = new ProductLike(user, product);

        try (SessionFactory factory = validatedSessionFactory()) {
            try (Session session = factory.openSession()) {
                var transaction = session.beginTransaction();
                session.persist(brand);
                session.persist(user);
                session.persist(product);
                session.persist(order);
                session.persist(like);
                transaction.commit();
            }
            try (Session session = factory.openSession()) {
                Product loaded = session.find(Product.class, product.getId());
                Order loadedOrder = session.find(Order.class, order.getId());
                assertThat(loaded.getName()).isEqualTo("😀".repeat(100));
                assertThat(loaded.getBrand().getName()).isEqualTo("한".repeat(100));
                assertThat(loaded.getPrice()).isEqualTo(Long.MAX_VALUE);
                assertThat(loaded.getStockQuantity()).isEqualTo(Integer.MAX_VALUE);
                assertThat(session.find(User.class, 1L).getBalance()).isEqualTo(Long.MAX_VALUE);
                assertThat(loadedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(loadedOrder.getTotalAmount()).isEqualTo(Long.MAX_VALUE);
                assertThat(loadedOrder.getPaidAmount()).isEqualTo(Long.MAX_VALUE);
                assertThat(loadedOrder.getConfirmedAt().toInstant()).isEqualTo(confirmedAt.toInstant());
                assertThat(loadedOrder.getItems()).hasSize(1);
                assertThat(loadedOrder.getItems().getFirst().getProductName()).isEqualTo("😀".repeat(100));
                assertThat(loadedOrder.getItems().getFirst().getUnitPrice()).isEqualTo(Long.MAX_VALUE);
                assertThat(session.find(ProductLike.class, like.getId()).getProduct().getId()).isEqualTo(product.getId());
            }
        }
        LocalDateTime databaseTime = jdbc.queryForObject("SELECT confirmed_at FROM `order` WHERE id = ?",
            (row, number) -> row.getObject(1, LocalDateTime.class), order.getId());
        assertThat(databaseTime.toInstant(ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-09-18T03:34:56.123456Z"));
    }

    @Test
    void definesRequiredPhysicalTypesCharsetEngineAndTimestampPrecision() {
        assertThat(jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
            String.class)).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(jdbc.queryForList("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
            String.class)).containsOnly("InnoDB");
        assertThat(jdbc.queryForList("SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
            String.class)).containsOnly("utf8mb4_general_ci");
        assertThat(jdbc.queryForList("""
            SELECT DATETIME_PRECISION FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE = 'datetime'
            """, Integer.class)).hasSize(12).containsOnly(6);
        assertThat(jdbc.queryForList("""
            SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN ('name','product_name_snapshot')
            """, Long.class)).hasSize(3).containsOnly(100L);
        for (String column : List.of("point_balance", "price", "unit_price_snapshot", "total_amount", "paid_amount")) {
            assertThat(jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = ?
                """, String.class, column)).isEqualTo("bigint");
        }
        for (String column : List.of("stock_quantity", "quantity")) {
            assertThat(jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = ?
                """, String.class, column)).isEqualTo("int");
        }
        assertThat(jdbc.queryForObject("""
            SELECT EXTRA FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'id'
            """, String.class)).doesNotContain("auto_increment");
    }

    @ParameterizedTest
    @MethodSource("invalidForeignKeys")
    void rejectsEveryMissingForeignKeyTargetWithoutChangingExistingRows(String statement) {
        seedRows();
        var before = snapshot();
        assertThatThrownBy(() -> jdbc.update(statement)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void enforcesCompositeLikeAndOrderItemUniquenessWithoutMakingIndividualColumnsUnique() {
        seedRows();
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO `like` (user_id,product_id,created_at) VALUES (1,101,CURRENT_TIMESTAMP(6))
            """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO order_item (order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity)
            VALUES (1001,101,'중복',100,1)
            """)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO `like` (user_id,product_id,created_at) VALUES (2,101,CURRENT_TIMESTAMP(6))");
        jdbc.update("INSERT INTO `like` (user_id,product_id,created_at) VALUES (1,102,CURRENT_TIMESTAMP(6))");
        jdbc.update("""
            INSERT INTO `order` (id,user_id,status,total_amount,created_at,updated_at)
            VALUES (1002,1,'DRAFT',100,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """);
        jdbc.update("""
            INSERT INTO order_item (order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity)
            VALUES (1002,101,'다른 주문',100,1)
            """);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("invalidStoredValues")
    void rejectsInvalidNumericStateAndMissingRequiredValuesAtTheDatabase(String statement) {
        seedRows();
        var before = snapshot();
        assertThatThrownBy(() -> jdbc.update(statement)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("invalidCheckValues")
    void rejectsInvalidNumericAndPaymentStatesByTheirNamedCheckConstraint(String statement, String constraint) {
        seedRows();
        var before = snapshot();
        assertCheckRejected(statement, constraint);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void acceptsConfirmedStateOnlyWithTheMatchingPaidAmountAndTimestamp() {
        seedRows();
        jdbc.update("""
            UPDATE `order` SET status = 'CONFIRMED', paid_amount = total_amount,
                confirmed_at = '2026-09-18 01:02:03.123456' WHERE id = 1001
            """);
        var before = snapshot();
        assertCheckRejected("UPDATE `order` SET paid_amount = 101 WHERE id = 1001", "ck_order_payment_state");
        assertCheckRejected("UPDATE `order` SET paid_amount = NULL WHERE id = 1001", "ck_order_payment_state");
        assertCheckRejected("UPDATE `order` SET confirmed_at = NULL WHERE id = 1001", "ck_order_payment_state");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void logicalDeletionPreservesReferencesAndPhysicalDeletionDoesNotCascade() {
        seedRows();
        jdbc.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = 101");
        var before = snapshot();
        for (String statement : List.of("DELETE FROM product WHERE id = 101", "DELETE FROM `order` WHERE id = 1001",
            "DELETE FROM `user` WHERE id = 1", "DELETE FROM brand WHERE id = 10")) {
            assertThatThrownBy(() -> jdbc.update(statement)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(1);
    }

    @Test
    void rejectsOversizedUnicodeNamesWithoutTruncatingExistingData() {
        seedRows();
        var before = snapshot();
        assertThatThrownBy(() -> jdbc.update("UPDATE product SET name = ? WHERE id = 101", "😀".repeat(101)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE order_item SET product_name_snapshot = ? WHERE id = 10001", "한".repeat(101)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void addsIndexesForProductBrandLookupLikeCountsAndOwnedRecentLists() {
        assertIndex("product", "idx_product_brand_deleted", "brand_id", "deleted_at");
        assertIndex("product", "idx_product_latest", "deleted_at", "created_at", "id");
        assertIndex("product", "idx_product_price", "deleted_at", "price", "id");
        assertIndex("like", "idx_like_product", "product_id");
        assertIndex("like", "idx_like_user_created", "user_id", "created_at", "id");
        assertIndex("order", "idx_order_user_created", "user_id", "created_at", "id");
        assertIndex("order", "idx_order_created", "created_at", "id");
    }

    @Test
    void reapplyingCreationScriptsFailsWithoutReplacingExistingRows() {
        seedRows();
        var before = snapshot();
        assertThatThrownBy(this::applySchema).isInstanceOf(ScriptStatementFailedException.class);
        assertThatThrownBy(() -> new ResourceDatabasePopulator(new ClassPathResource("db/schema/002-user.sql"))
            .execute(dataSource)).isInstanceOf(ScriptStatementFailedException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    private void assertCheckRejected(String statement, String constraint) {
        Throwable failure = catchThrowable(() -> jdbc.update(statement));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(failure.getCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getErrorCode()).isEqualTo(3819);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    private void assertIndex(String table, String name, String... columns) {
        assertThat(jdbc.queryForList("""
            SELECT COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX
            """, String.class, table, name)).containsExactly(columns);
    }

    private void applySchema() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        SCRIPTS.forEach(script -> populator.addScript(new ClassPathResource("db/schema/" + script)));
        populator.execute(dataSource);
    }

    private SessionFactory validatedSessionFactory() {
        Configuration configuration = new Configuration();
        for (Class<?> type : List.of(Brand.class, User.class, Product.class, ProductLike.class, Order.class, OrderItem.class)) {
            configuration.addAnnotatedClass(type);
        }
        configuration.addAttributeConverter(BrandNameConverter.class, true);
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.timezone.default_storage", "NORMALIZE_UTC");
        configuration.setProperty("hibernate.jdbc.time_zone", "UTC");
        return configuration.buildSessionFactory();
    }

    private List<List<Map<String, Object>>> snapshot() {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        TABLES.forEach(table -> rows.add(jdbc.queryForList("SELECT * FROM `" + table + "` ORDER BY id")));
        return rows;
    }

    private void seedRows() {
        jdbc.update("INSERT INTO brand (id,name,created_at,updated_at) VALUES (10,'브랜드',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
        jdbc.update("""
            INSERT INTO `user` (id,point_balance,created_at,updated_at)
            VALUES (1,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),(2,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """);
        jdbc.update("""
            INSERT INTO product (id,brand_id,name,price,stock_quantity,created_at,updated_at)
            VALUES (101,10,'상품',100,5,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
                (102,10,'상품',1,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """);
        jdbc.update("""
            INSERT INTO `order` (id,user_id,status,total_amount,created_at,updated_at)
            VALUES (1001,1,'DRAFT',100,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """);
        jdbc.update("""
            INSERT INTO order_item (id,order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity)
            VALUES (10001,1001,101,'상품',100,1)
            """);
        jdbc.update("INSERT INTO `like` (id,user_id,product_id,created_at) VALUES (100001,1,101,CURRENT_TIMESTAMP(6))");
    }

    private static Stream<String> invalidForeignKeys() {
        return Stream.of(
            "INSERT INTO product (brand_id,name,price,stock_quantity,created_at,updated_at) VALUES (999,'상품',1,0,NOW(6),NOW(6))",
            "INSERT INTO `like` (user_id,product_id,created_at) VALUES (999,101,NOW(6))",
            "INSERT INTO `like` (user_id,product_id,created_at) VALUES (1,999,NOW(6))",
            "INSERT INTO `order` (user_id,status,total_amount,created_at,updated_at) VALUES (999,'DRAFT',1,NOW(6),NOW(6))",
            "INSERT INTO order_item (order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity) VALUES (999,101,'상품',1,1)",
            "INSERT INTO order_item (order_id,product_id,product_name_snapshot,unit_price_snapshot,quantity) VALUES (1001,999,'상품',1,1)"
        );
    }

    private static Stream<Arguments> invalidCheckValues() {
        return Stream.of(
            Arguments.of("UPDATE `user` SET point_balance = -1 WHERE id = 1", "ck_user_nonnegative_balance"),
            Arguments.of("UPDATE product SET price = 0 WHERE id = 101", "ck_product_positive_price"),
            Arguments.of("UPDATE product SET stock_quantity = -1 WHERE id = 101", "ck_product_nonnegative_stock"),
            Arguments.of("UPDATE order_item SET quantity = 0 WHERE id = 10001", "ck_order_item_positive_quantity"),
            Arguments.of("UPDATE order_item SET unit_price_snapshot = -1 WHERE id = 10001", "ck_order_item_nonnegative_price"),
            Arguments.of("UPDATE `order` SET total_amount = -1 WHERE id = 1001", "ck_order_nonnegative_total"),
            Arguments.of("UPDATE `order` SET paid_amount = -1 WHERE id = 1001", "ck_order_nonnegative_payment"),
            Arguments.of("UPDATE `order` SET status = 'CONFIRMED' WHERE id = 1001", "ck_order_payment_state"),
            Arguments.of("UPDATE `order` SET paid_amount = 100 WHERE id = 1001", "ck_order_payment_state"),
            Arguments.of("UPDATE `order` SET confirmed_at = NOW(6) WHERE id = 1001", "ck_order_payment_state")
        );
    }

    private static Stream<String> invalidStoredValues() {
        return Stream.of(
            "UPDATE `order` SET status = 'UNKNOWN' WHERE id = 1001",
            "UPDATE product SET brand_id = NULL WHERE id = 101",
            "UPDATE product SET name = NULL WHERE id = 101",
            "UPDATE product SET created_at = NULL WHERE id = 101",
            "UPDATE `like` SET user_id = NULL WHERE id = 100001",
            "UPDATE `order` SET user_id = NULL WHERE id = 1001",
            "UPDATE order_item SET product_name_snapshot = NULL WHERE id = 10001"
        );
    }
}
