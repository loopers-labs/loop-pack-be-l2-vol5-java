package com.loopers.infrastructure.initializer.mall;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("benchmark-seed")
@ConditionalOnProperty(prefix = "benchmark.seed", name = "enabled", havingValue = "true")
// 기존 DB를 비우지 않고, 비어 있는 전용 스키마에 INSERT 전용 seed를 원자적으로 적재한다.
public class JdbcProductSeedLoader {
    private static final String BENCHMARK_SCHEMA = "week4_r01_index_benchmark";
    private static final List<String> TABLES = List.of("brands", "users", "wallets", "products", "product_likes");
    private static final Map<String, String> BASELINE_INDEXES = Map.of(
        "idx_products_deleted_brand_created", "deleted A,brand_id A,created_at D,id D",
        "idx_products_deleted_brand_price", "deleted A,brand_id A,price A,id D",
        "idx_products_deleted_like", "deleted A,like_count D,id D",
        "idx_products_deleted_brand_like", "deleted A,brand_id A,like_count D,id D"
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Resource script;
    private final String expectedSchema;
    private final ProductSeedResult expected;

    @Autowired
    public JdbcProductSeedLoader(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this(dataSource, transactionManager, new ClassPathResource("seed/product-benchmark.sql"),
            BENCHMARK_SCHEMA, ProductSeedResult.benchmark());
    }

    JdbcProductSeedLoader(DataSource dataSource, PlatformTransactionManager transactionManager,
                          Resource script, String expectedSchema, ProductSeedResult expected) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(3600);
        this.script = script;
        this.expectedSchema = expectedSchema;
        this.expected = expected;
    }

    public ProductSeedResult load() {
        return Objects.requireNonNull(transaction.execute(status -> {
            requireEmptyBenchmarkSchema();
            requireBaselineIndexes();

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(script);
            populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
            populator.execute(dataSource);

            requireConsistentRelations();
            ProductSeedResult result = readResult();
            if (!expected.equals(result)) {
                throw new IllegalStateException("seed 검증 기대값 불일치: expected=" + expected + ", actual=" + result);
            }
            return result;
        }));
    }

    private void requireEmptyBenchmarkSchema() {
        String actualSchema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!expectedSchema.equals(actualSchema)) {
            throw new IllegalStateException("seed 대상 DB가 아닙니다: " + actualSchema);
        }
        Long transactionalTables = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND engine = 'InnoDB'
                AND table_name IN ('brands', 'users', 'wallets', 'products', 'product_likes')
            """, Long.class);
        if (!Long.valueOf(TABLES.size()).equals(transactionalTables)) {
            throw new IllegalStateException("seed 대상 5개 테이블은 모두 InnoDB여야 합니다.");
        }
        for (String table : TABLES) {
            Long exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + " LIMIT 1)", Long.class);
            if (!Long.valueOf(0).equals(exists)) {
                throw new IllegalStateException("seed 대상 테이블이 비어 있지 않습니다: " + table);
            }
        }
    }

    private void requireBaselineIndexes() {
        Map<String, String> indexes = jdbc.query("""
            SELECT index_name, GROUP_CONCAT(CONCAT(column_name, ' ', collation)
                ORDER BY seq_in_index SEPARATOR ',') AS definition
            FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'products'
            GROUP BY index_name
            """, (row, rowNumber) -> Map.entry(row.getString("index_name"), row.getString("definition")))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (indexes.size() != BASELINE_INDEXES.size() + 1 || !"id A".equals(indexes.get("PRIMARY"))
            || !indexes.entrySet().containsAll(BASELINE_INDEXES.entrySet())) {
            throw new IllegalStateException("변경 전 상품 인덱스 4개와 추가 인덱스 부재를 확인해야 합니다: " + indexes);
        }
    }

    private void requireConsistentRelations() {
        requireZero("지갑 초기 잔액", "SELECT COUNT(*) FROM wallets WHERE balance <> 0");
        requireZero("고아 참조", """
            SELECT
                (SELECT COUNT(*) FROM products p LEFT JOIN brands b ON b.id = p.brand_id WHERE b.id IS NULL)
                + (SELECT COUNT(*) FROM wallets w LEFT JOIN users u ON u.id = w.user_id WHERE u.id IS NULL)
                + (SELECT COUNT(*) FROM product_likes l LEFT JOIN products p ON p.id = l.product_id
                    LEFT JOIN users u ON u.id = l.user_id WHERE p.id IS NULL OR u.id IS NULL)
            """);
        requireZero("중복 좋아요", """
            SELECT COUNT(*) FROM (SELECT product_id, user_id FROM product_likes
                GROUP BY product_id, user_id HAVING COUNT(*) > 1) duplicates
            """);
        requireZero("좋아요 집계", """
            SELECT COUNT(*) FROM products p
            LEFT JOIN (SELECT product_id, COUNT(*) AS actual_count FROM product_likes GROUP BY product_id) l
                ON l.product_id = p.id
            WHERE p.like_count <> COALESCE(l.actual_count, 0)
            """);
        requireZero("삭제 브랜드의 활성 상품", """
            SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id
            WHERE b.deleted = true AND p.deleted = false
            """);
    }

    private void requireZero(String label, String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        if (!Long.valueOf(0).equals(count)) {
            throw new IllegalStateException("seed " + label + " 검증 실패: " + count);
        }
    }

    private ProductSeedResult readResult() {
        long brands = count("brands");
        long users = count("users");
        long wallets = count("wallets");
        long likes = count("product_likes");
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS products,
                COALESCE(SUM(deleted = false), 0) AS active_products,
                COALESCE(SUM(deleted = true), 0) AS deleted_products,
                COALESCE(SUM(price), 0) AS price_sum,
                COALESCE(SUM(like_count = 0), 0) AS zero_like_products,
                COALESCE(SUM(like_count = 100), 0) AS popular_products,
                COALESCE(SUM(brand_id = 7 AND deleted = false), 0) AS brand7_active_products,
                COALESCE(SUM(CASE WHEN brand_id = 7 AND deleted = false THEN price ELSE 0 END), 0) AS brand7_price_sum
            FROM products
            """, (row, rowNumber) -> new ProductSeedResult(brands, users, wallets, row.getLong("products"),
                row.getLong("active_products"), row.getLong("deleted_products"), row.getLong("price_sum"), likes,
                row.getLong("zero_like_products"), row.getLong("popular_products"), row.getLong("brand7_active_products"),
                row.getLong("brand7_price_sum")));
    }

    private long count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
}
