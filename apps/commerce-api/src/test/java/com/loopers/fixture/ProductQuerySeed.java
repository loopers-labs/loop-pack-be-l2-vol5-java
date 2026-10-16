package com.loopers.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProductQuerySeed {

    public static final int BRAND_COUNT = 100;
    public static final int USER_COUNT = 1_000;
    public static final int PRODUCT_COUNT = 1_000_000;
    public static final int BATCH_SIZE = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ProductQuerySeed.class);
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime DELETED_AT = BASE_TIME.plusDays(30);
    private static final String INSERT_PRODUCT = """
        INSERT INTO products (brand_id, name, price, stock, created_at, updated_at, deleted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    private static final String INSERT_LIKE = """
        INSERT INTO likes (user_id, product_id, created_at, updated_at) VALUES (?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public ProductQuerySeed(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SeedResult load() {
        return load(PRODUCT_COUNT);
    }

    public SeedResult load(int productCount) {
        if (productCount <= 0) {
            throw new IllegalArgumentException("상품 수는 양수여야 합니다.");
        }
        requireEmptyTables();
        List<Long> brandIds = insertWithGeneratedIds("""
            INSERT INTO brands (name, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?)
            """, BRAND_COUNT, (statement, index) -> {
                statement.setString(1, String.format(Locale.ROOT, "query-seed-brand-%03d", index + 1));
                statement.setObject(2, BASE_TIME);
                statement.setObject(3, index + 1 == BRAND_COUNT ? DELETED_AT : BASE_TIME);
                setDeletedAt(statement, 4, index + 1 == BRAND_COUNT);
            });
        List<Long> userIds = insertWithGeneratedIds("""
            INSERT INTO users (created_at, updated_at) VALUES (?, ?)
            """, USER_COUNT, (statement, index) -> {
                statement.setObject(1, BASE_TIME);
                statement.setObject(2, BASE_TIME);
            });

        long likeCount = 0;
        List<LikeRow> likes = new ArrayList<>(BATCH_SIZE);
        for (long first = 1; first <= productCount; first += BATCH_SIZE) {
            int firstOrdinal = (int) first;
            int batchSize = Math.min(BATCH_SIZE, productCount - firstOrdinal + 1);
            List<Long> productIds = insertWithGeneratedIds(INSERT_PRODUCT, batchSize, (statement, index) -> {
                int ordinal = firstOrdinal + index;
                boolean deleted = ordinal % BRAND_COUNT == BRAND_COUNT - 1 || ordinal % 500 == 0;
                LocalDateTime createdAt = BASE_TIME.plusSeconds(ordinal / 4L);
                statement.setLong(1, brandIds.get(ordinal % BRAND_COUNT));
                statement.setString(2, String.format(Locale.ROOT, "query-seed-product-%07d", ordinal));
                statement.setLong(3, ((ordinal * 37L) % 100_000) + 100);
                statement.setLong(4, 100 + ordinal % 51);
                statement.setObject(5, createdAt);
                statement.setObject(6, deleted ? DELETED_AT : createdAt);
                setDeletedAt(statement, 7, deleted);
            });
            for (int index = 0; index < batchSize; index++) {
                int ordinal = firstOrdinal + index;
                int count = ordinal % 1_000 == 6 ? 200 : ordinal % 11;
                for (int likeIndex = 0; likeIndex < count; likeIndex++) {
                    int userIndex = (int) ((ordinal * 37L + likeIndex) % USER_COUNT);
                    likes.add(new LikeRow(userIds.get(userIndex), productIds.get(index), BASE_TIME.plusSeconds(ordinal / 4L)));
                    if (likes.size() == BATCH_SIZE) {
                        insertLikes(likes);
                        likes.clear();
                    }
                }
                likeCount += count;
            }
            if (!likes.isEmpty()) {
                insertLikes(likes);
                likes.clear();
            }
            int loaded = firstOrdinal + batchSize - 1;
            if (loaded % 100_000 == 0) {
                log.info("상품 조회 seed 적재: products={}/{}, likes={}", loaded, productCount, likeCount);
            }
        }

        Map<Integer, Long> brandsByOrdinal = new LinkedHashMap<>();
        for (int index = 0; index < brandIds.size(); index++) {
            brandsByOrdinal.put(index + 1, brandIds.get(index));
        }
        return new SeedResult(brandsByOrdinal, productCount, likeCount);
    }

    private void requireEmptyTables() {
        for (String table : List.of("brands", "users", "products", "likes")) {
            Boolean hasRows = jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + ")", Boolean.class);
            if (Boolean.TRUE.equals(hasRows)) {
                throw new IllegalStateException("seed 대상 테이블이 비어 있지 않습니다: " + table);
            }
        }
    }

    private List<Long> insertWithGeneratedIds(String sql, int rowCount, RowSetter setter) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.batchUpdate(
            connection -> connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS),
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    setter.setValues(statement, index);
                }

                @Override
                public int getBatchSize() {
                    return rowCount;
                }
            },
            keys
        );
        if (keys.getKeyList().size() != rowCount) {
            throw new IllegalStateException("배치 행 수와 자동 생성 PK 수가 일치하지 않습니다.");
        }
        return keys.getKeyList().stream()
            .map(key -> ((Number) key.values().iterator().next()).longValue())
            .toList();
    }

    private void insertLikes(List<LikeRow> likes) {
        jdbcTemplate.batchUpdate(INSERT_LIKE, likes, BATCH_SIZE, (statement, like) -> {
            statement.setLong(1, like.userId());
            statement.setLong(2, like.productId());
            statement.setObject(3, like.createdAt());
            statement.setObject(4, like.createdAt());
        });
    }

    private void setDeletedAt(PreparedStatement statement, int parameter, boolean deleted) throws SQLException {
        if (deleted) {
            statement.setObject(parameter, DELETED_AT);
        } else {
            statement.setNull(parameter, Types.TIMESTAMP);
        }
    }

    @FunctionalInterface
    private interface RowSetter {
        void setValues(PreparedStatement statement, int index) throws SQLException;
    }

    private record LikeRow(long userId, long productId, LocalDateTime createdAt) {
    }

    public record SeedResult(Map<Integer, Long> brandIds, int productCount, long likeCount) {

        public SeedResult {
            brandIds = Map.copyOf(brandIds);
        }

        public long brandId(int ordinal) {
            Long id = brandIds.get(ordinal);
            if (id == null) {
                throw new IllegalArgumentException("존재하지 않는 브랜드 순번입니다: " + ordinal);
            }
            return id;
        }
    }
}
