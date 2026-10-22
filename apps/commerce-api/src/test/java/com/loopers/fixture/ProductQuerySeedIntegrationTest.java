package com.loopers.fixture;

import com.loopers.infrastructure.product.ProductJpaEntity;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("상품 조회 측정용 JDBC seed")
class ProductQuerySeedIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("작은 seed의 필수 값·분포·삭제 상태·좋아요 관계를 실제 DB에서 확인한다.")
    void loadsReproducibleSmallSeed() {
        ProductQuerySeed.SeedResult seed = new ProductQuerySeed(jdbcTemplate).load(10_000);

        assertThat(count("brands")).isEqualTo(100);
        assertThat(count("users")).isEqualTo(1_000);
        assertThat(count("products")).isEqualTo(10_000);
        assertThat(count("likes")).isEqualTo(51_948);
        assertThat(seed.productCount()).isEqualTo(10_000);
        assertThat(seed.likeCount()).isEqualTo(51_948);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(price) FROM products", Long.class))
            .isEqualTo(472_685_000L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE deleted_at IS NOT NULL", Long.class))
            .isEqualTo(120);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brands WHERE deleted_at IS NOT NULL", Long.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE brand_id = ?", Long.class, seed.brandId(7)))
            .isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(price) FROM products WHERE brand_id = ?", Long.class, seed.brandId(7)))
            .isEqualTo(4_647_200L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM products p LEFT JOIN likes l ON l.product_id = p.id
            WHERE l.id IS NULL
            """, Long.class)).isEqualTo(908);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM (SELECT product_id FROM likes GROUP BY product_id HAVING COUNT(*) = 200) popular
            """, Long.class)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM likes l
            LEFT JOIN users u ON u.id = l.user_id LEFT JOIN products p ON p.id = l.product_id
            WHERE u.id IS NULL OR p.id IS NULL
            """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id
            WHERE b.deleted_at IS NOT NULL AND p.deleted_at IS NULL
            """, Long.class)).isZero();
        Long productId = jdbcTemplate.queryForObject(
            "SELECT id FROM products WHERE name = 'query-seed-product-0000006'", Long.class
        );
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            ProductJpaEntity product = entityManager.find(ProductJpaEntity.class, productId);
            assertThat(product.getBrand().getId()).isEqualTo(seed.brandId(7));
            assertThat(product.getPrice()).isEqualTo(322L);
            assertThat(product.getStock()).isEqualTo(106L);
            assertThat(product.getCreatedAt().toInstant()).isEqualTo(Instant.parse("2026-01-01T00:00:01Z"));
        });
    }

    @Test
    @DisplayName("자동 증가 PK가 순번과 달라도 같은 데이터와 실제 사용자·상품 관계를 만든다.")
    void usesGeneratedIdsInsteadOfAssumingOrdinalIsId() {
        jdbcTemplate.execute("ALTER TABLE brands AUTO_INCREMENT = 1001");
        jdbcTemplate.execute("ALTER TABLE users AUTO_INCREMENT = 2001");
        jdbcTemplate.execute("ALTER TABLE products AUTO_INCREMENT = 3001");

        ProductQuerySeed.SeedResult first = new ProductQuerySeed(jdbcTemplate).load(1_006);
        Map<String, Object> shiftedValues = jdbcTemplate.queryForMap("""
            SELECT p.price, p.stock, p.created_at, COUNT(l.id) AS like_count
            FROM products p LEFT JOIN likes l ON l.product_id = p.id
            WHERE p.name = 'query-seed-product-0001006'
            GROUP BY p.id
            """);
        assertThat(first.brandId(7)).isEqualTo(1_007L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM likes l JOIN users u ON u.id = l.user_id JOIN products p ON p.id = l.product_id
            WHERE p.name = 'query-seed-product-0001006' AND u.id >= 2001 AND p.id >= 3001
            """, Long.class)).isEqualTo(200);

        databaseCleanUp.truncateAllTables();
        ProductQuerySeed.SeedResult second = new ProductQuerySeed(jdbcTemplate).load(1_006);
        Map<String, Object> resetValues = jdbcTemplate.queryForMap("""
            SELECT p.price, p.stock, p.created_at, COUNT(l.id) AS like_count
            FROM products p LEFT JOIN likes l ON l.product_id = p.id
            WHERE p.name = 'query-seed-product-0001006'
            GROUP BY p.id
            """);
        assertThat(second.brandId(7)).isEqualTo(7L);
        assertThat(resetValues).isEqualTo(shiftedValues);
        assertThat(second.likeCount()).isEqualTo(first.likeCount());
    }

    @Test
    @DisplayName("실행용 seed는 loopers DB 연결을 허용한다.")
    void runnerAcceptsTargetDatabase() {
        assertThatCode(() -> ProductQuerySeedRunner.verifyTargetDatabase(jdbcTemplate)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실행용 seed는 지정한 실습 DB 이외의 연결을 거절한다.")
    void runnerRejectsOtherDatabase() {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        JdbcTemplate rootJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "root", password));
        rootJdbcTemplate.execute("CREATE DATABASE loopers_seed_other_test");
        try {
            JdbcTemplate otherJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                url.replace("/loopers", "/loopers_seed_other_test"), "root", password
            ));
            assertThatThrownBy(() -> ProductQuerySeedRunner.verifyTargetDatabase(otherJdbcTemplate))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopers");
        } finally {
            rootJdbcTemplate.execute("DROP DATABASE loopers_seed_other_test");
        }
        assertThat(count("products")).isZero();
        assertThat(count("brands")).isZero();
    }

    @Test
    @DisplayName("기존 데이터가 있으면 seed 적재를 거절하고 기존 행을 보존한다.")
    void rejectsNonEmptyTables() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbcTemplate.update("INSERT INTO brands (name, created_at, updated_at) VALUES (?, ?, ?)", "Existing", now, now);

        assertThatThrownBy(() -> new ProductQuerySeed(jdbcTemplate).load(10_000))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("brands");
        assertThat(count("brands")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM brands", String.class)).isEqualTo("Existing");
        assertThat(count("products")).isZero();
        assertThat(count("users")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("상품 수가 양수가 아니면 적재하지 않는다.")
    void rejectsInvalidProductCount(int productCount) {
        assertThatThrownBy(() -> new ProductQuerySeed(jdbcTemplate).load(productCount))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("brands")).isZero();
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
