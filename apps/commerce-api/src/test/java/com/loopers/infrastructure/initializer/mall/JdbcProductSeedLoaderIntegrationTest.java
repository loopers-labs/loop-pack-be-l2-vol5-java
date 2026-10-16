package com.loopers.infrastructure.initializer.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class JdbcProductSeedLoaderIntegrationTest {
    private static final ProductSeedResult EXPECTED = new ProductSeedResult(2, 2, 2, 2, 1, 1, 3000, 2, 1, 0, 1, 1000);
    private static final String REMOVE_LIST_INDEXES = """
        ALTER TABLE products
            DROP INDEX idx_products_deleted_created,
            DROP INDEX idx_products_deleted_price
        """;
    private static final String RESTORE_LIST_INDEXES = """
        ALTER TABLE products
            ADD INDEX idx_products_deleted_created (deleted, created_at DESC, id DESC),
            ADD INDEX idx_products_deleted_price (deleted, price ASC, id DESC)
        """;
    private static final String SCRIPT = """
        INSERT INTO brands (id, name, deleted, created_at, updated_at) VALUES
            (7, 'active', false, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
            (991, 'deleted', true, '2026-10-01 00:00:00', '2026-10-01 00:00:00');
        INSERT INTO users (id) VALUES (1), (2);
        INSERT INTO wallets (user_id, balance) VALUES (1, 0), (2, 0);
        INSERT INTO products
            (id, brand_id, name, price, stock, like_count, deleted, created_at, updated_at) VALUES
            (1, 7, 'active', 1000, 0, 2, false, '2026-10-01 00:00:00', '2026-10-01 00:00:00'),
            (2, 991, 'deleted', 2000, 5, 0, true, '2026-10-01 00:00:00', '2026-10-01 00:00:00');
        INSERT INTO product_likes (user_id, product_id, created_at) VALUES
            (1, 1, '2026-10-01 01:00:00'), (2, 1, '2026-10-01 01:00:00');
        """;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private boolean testContainerVerified;
    private boolean baselineIndexesPrepared;

    @BeforeEach
    void prepareBaselineIndexes() throws SQLException {
        Object configuredContainer = ReflectionTestUtils.getField(MySqlTestContainersConfig.class, "mySqlContainer");
        assertThat(configuredContainer).isNotNull();
        assertThat(configuredContainer.getClass().getName()).isEqualTo("org.testcontainers.containers.MySQLContainer");
        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(configuredContainer, "isRunning"))
            .as("Testcontainers MySQL 실행 여부").isTrue();
        String testDatabaseUrl = ReflectionTestUtils.invokeMethod(configuredContainer, "getJdbcUrl");
        String testDatabaseName = ReflectionTestUtils.invokeMethod(configuredContainer, "getDatabaseName");
        assertThat(testDatabaseUrl).isNotBlank();
        try (Connection connection = dataSource.getConnection()) {
            // 설정 클래스는 URL 옵션을 생략하므로 호스트·매핑 포트·DB 경로를 비교한다.
            assertThat(connection.getMetaData().getURL().split("\\?", 2)[0])
                .as("실제 DataSource가 연결한 Testcontainers DB")
                .isEqualTo(testDatabaseUrl.split("\\?", 2)[0]);
            assertThat(connection.getCatalog()).isEqualTo(testDatabaseName);
        }
        testContainerVerified = true;
        executeSql(REMOVE_LIST_INDEXES);
        baselineIndexesPrepared = true;
    }

    @AfterEach
    void tearDown() {
        if (!testContainerVerified) {
            return;
        }
        try {
            databaseCleanUp.truncateAllTables();
        } finally {
            if (baselineIndexesPrepared) {
                executeSql(RESTORE_LIST_INDEXES);
            }
        }
    }

    @DisplayName("실제 MySQL에서 전체 적재와 관계 검증을 마친 결과를 commit한다")
    @Test
    void commitsVerifiedSeed() {
        // arrange
        JdbcProductSeedLoader loader = loader(SCRIPT, EXPECTED);

        // act
        ProductSeedResult result = loader.load();

        // assert
        assertThat(result).isEqualTo(EXPECTED);
        assertThat(count("products")).isEqualTo(2);
        assertThat(count("product_likes")).isEqualTo(2);
    }

    @DisplayName("운영 생성자는 전용 실험 DB가 아니면 적재 전에 거절한다")
    @Test
    void rejectsDifferentDatabaseBeforeWriting() {
        // arrange
        JdbcProductSeedLoader loader = new JdbcProductSeedLoader(dataSource, transactionManager);

        // act, assert
        assertThatThrownBy(loader::load).isInstanceOf(IllegalStateException.class).hasMessageContaining("DB");
        assertThat(count("brands")).isZero();
    }

    @DisplayName("대상 테이블에 기존 데이터가 있으면 이를 보존하고 적재를 거절한다")
    @Test
    void preservesExistingData() {
        // arrange
        Brand existing = brandRepository.save(Brand.create("기존 브랜드", null));

        // act, assert
        assertThatThrownBy(loader(SCRIPT, EXPECTED)::load)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("brands");
        assertThat(brandRepository.findById(existing.getId())).isPresent();
        assertThat(count("users")).isZero();
    }

    @DisplayName("이름이 다른 추가 인덱스가 있어도 변경 전 기준선이 아니면 적재를 거절한다")
    @Test
    void rejectsUnexpectedIndexWithoutWriting() {
        // arrange
        executeSql("CREATE INDEX idx_seed_test_price ON products (price)");

        try {
            // act, assert
            assertThatThrownBy(loader(SCRIPT, EXPECTED)::load)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("인덱스");
            assertAllSeedTablesEmpty();
        } finally {
            executeSql("DROP INDEX idx_seed_test_price ON products");
        }
    }

    @DisplayName("전체 최신순·가격순 인덱스가 추가된 6개 상태에서는 기준선 seed를 적재 전에 거절한다")
    @Test
    void rejectsNewListIndexesWithoutWriting() {
        // arrange
        executeSql(RESTORE_LIST_INDEXES);

        try {
            // act, assert
            assertThatThrownBy(loader(SCRIPT, EXPECTED)::load)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("인덱스");
            assertAllSeedTablesEmpty();
        } finally {
            executeSql(REMOVE_LIST_INDEXES);
        }
    }

    @DisplayName("적재 SQL이 중간에 실패하면 앞에서 넣은 모든 행을 rollback한다")
    @Test
    void rollsBackEarlierInsertsWhenSqlFails() {
        // arrange
        String failingScript = SCRIPT + "INSERT INTO users (id) VALUES (1);";

        // act, assert
        assertThatThrownBy(loader(failingScript, EXPECTED)::load).isInstanceOf(RuntimeException.class);
        assertAllSeedTablesEmpty();
    }

    @DisplayName("적재 후 기대 건수가 다르면 전체 적재를 rollback한다")
    @Test
    void rollsBackWhenExpectedCountsDiffer() {
        // arrange
        ProductSeedResult wrongExpected = new ProductSeedResult(2, 2, 2, 3, 1, 1, 3000, 2, 1, 0, 1, 1000);

        // act, assert
        assertThatThrownBy(loader(SCRIPT, wrongExpected)::load)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("기대");
        assertAllSeedTablesEmpty();
    }

    @DisplayName("좋아요 원본과 집계가 다르면 전체 적재를 rollback한다")
    @Test
    void rollsBackWhenLikeCountDiffersFromRelations() {
        // arrange
        String inconsistentScript = SCRIPT.replace("1000, 0, 2, false", "1000, 0, 1, false");

        // act, assert
        assertThatThrownBy(loader(inconsistentScript, EXPECTED)::load)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("좋아요 집계");
        assertAllSeedTablesEmpty();
    }

    @DisplayName("존재하지 않는 브랜드를 참조하면 전체 적재를 rollback한다")
    @Test
    void rollsBackWhenReferenceIsMissing() {
        // arrange
        String inconsistentScript = SCRIPT.replace("(2, 991, 'deleted'", "(2, 992, 'deleted'");

        // act, assert
        assertThatThrownBy(loader(inconsistentScript, EXPECTED)::load)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("고아 참조");
        assertAllSeedTablesEmpty();
    }

    @DisplayName("초기 지갑 잔액이 0이 아니면 전체 적재를 rollback한다")
    @Test
    void rollsBackWhenWalletIsNotZero() {
        // arrange
        String inconsistentScript = SCRIPT.replace("VALUES (1, 0), (2, 0)", "VALUES (1, 1), (2, 0)");

        // act, assert
        assertThatThrownBy(loader(inconsistentScript, EXPECTED)::load)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("지갑 초기 잔액");
        assertAllSeedTablesEmpty();
    }

    @DisplayName("삭제 브랜드에 활성 상품이 있으면 전체 적재를 rollback한다")
    @Test
    void rollsBackWhenDeletedBrandHasActiveProducts() {
        // arrange
        String inconsistentScript = SCRIPT.replace("2000, 5, 0, true", "2000, 5, 0, false");

        // act, assert
        assertThatThrownBy(loader(inconsistentScript, EXPECTED)::load)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("삭제 브랜드");
        assertAllSeedTablesEmpty();
    }

    private JdbcProductSeedLoader loader(String script, ProductSeedResult expected) {
        return new JdbcProductSeedLoader(dataSource, transactionManager,
            new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)), "loopers", expected);
    }

    private long count(String table) {
        return new TransactionTemplate(transactionManager).execute(status ->
            ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult()).longValue());
    }

    private void executeSql(String sql) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery(sql).executeUpdate());
    }

    private void assertAllSeedTablesEmpty() {
        assertThat(count("brands")).isZero();
        assertThat(count("users")).isZero();
        assertThat(count("wallets")).isZero();
        assertThat(count("products")).isZero();
        assertThat(count("product_likes")).isZero();
    }
}
