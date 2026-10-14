package com.loopers.seed;

import com.loopers.brand.domain.BrandModel;
import com.loopers.product.domain.ProductModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * W4 측정용 상품 카탈로그 seed. 난수 없이 순번 i로만 값을 정해, 몇 번을 만들어도 같은 데이터가 나온다.
 * 빈 테이블에 id = 순번으로 넣으므로 "브랜드 순번 7"은 곧 brand_id 7이다.
 * 로컬 실행(CatalogSeedRunner)과 테스트가 이 클래스 하나를 함께 쓴다 — 서로 다른 데이터를 재지 않기 위해서다.
 * 패키지를 support 아래가 아니라 따로 둔 이유: brand·product가 support.error를 쓰므로, support가 그 둘을 알면 순환이 생긴다 (ArchitectureTest).
 */
public class CatalogSeeder {

    private static final int CHUNK_SIZE = 1_000;
    private static final LocalDateTime SEEDED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final List<String> TABLES = List.of("brands", "products", "users", "likes");

    private final JdbcTemplate jdbcTemplate;

    public CatalogSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isEmpty() {
        return TABLES.stream().allMatch(table ->
            Boolean.FALSE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + ")", Boolean.class))
        );
    }

    /**
     * 브랜드·상품·사용자·좋아요를 순서대로 넣는다. 이미 데이터가 있으면 순번과 id가 어긋나므로 거절한다.
     */
    public void seed(int brands, int products, int users) {
        if (!isEmpty()) {
            throw new IllegalStateException("seed는 빈 테이블에만 넣을 수 있습니다.");
        }

        insert(
            "INSERT INTO brands (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            brands,
            i -> {
                // 저장은 JDBC로 하되 값은 도메인 생성자를 거친다 — BRD-01 검증과 이름 다듬기가 운영과 같다.
                BrandModel brand = new BrandModel("브랜드 %03d".formatted(i), "seed 브랜드 " + i);
                return new Object[] {i, brand.getName(), brand.getDescription(), SEEDED_AT, SEEDED_AT};
            }
        );
        insert(
            "INSERT INTO products (id, brand_id, name, price, stock, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            products,
            i -> {
                ProductModel product = new ProductModel((long) brandOf(i, brands), "상품 %07d".formatted(i), priceOf(i), stockOf(i));  // PRD-01
                return new Object[] {
                    i, product.getBrandId(), product.getName(), product.getPrice().amount(), product.getStock(),
                    SEEDED_AT, SEEDED_AT, isDeleted(i) ? SEEDED_AT : null
                };
            }
        );
        insert(
            "INSERT INTO users (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            users,
            i -> new Object[] {i, "사용자 %05d".formatted(i), SEEDED_AT, SEEDED_AT}
        );
        insertLikes(products, users);
    }

    /** 브랜드마다 같은 수의 상품이 돌아가며 배정된다. */
    static int brandOf(int i, int brands) {
        return (i % brands) + 1;
    }

    /** 100 ~ 100,099원. 37이 100,000과 서로소라 10만 개까지 가격이 겹치지 않고 고르게 퍼진다. */
    static long priceOf(int i) {
        return ((i * 37L) % 100_000) + 100;
    }

    /** 0 ~ 49개. 50번째마다 품절이다. */
    static int stockOf(int i) {
        return i % 50;
    }

    /** 97번째마다 삭제된 상품이다 — 조회의 삭제 조건이 실제로 행을 거르게 한다. */
    static boolean isDeleted(int i) {
        return i % 97 == 0;
    }

    /**
     * 좋아요 수 분포: 10번째마다 0개, 1,000개마다 하나씩 인기 상품(300개부터 50개씩 증가), 나머지는 0 ~ 19개(동점 다수).
     * i / 100 항이 없으면 같은 브랜드(i % 100 이 같은 상품)의 좋아요 수가 전부 같아져 브랜드 안 좋아요순이 의미를 잃는다.
     */
    static int likeCountOf(int i, int users) {
        if (i % 10 == 0) {
            return 0;
        }
        if (i % 1_000 == 1) {
            return Math.min(users, 300 + (i / 1_000) * 50);
        }
        return Math.min(users, (i * 7 + (i / 100) * 3) % 20);
    }

    /** 상품 i의 k번째 좋아요를 누른 사용자. k가 연속이라 한 상품 안에서 사용자가 겹치지 않는다. */
    static int likerOf(int i, int k, int users) {
        return (int) ((i * 13L + k) % users) + 1;
    }

    private void insertLikes(int products, int users) {
        String sql = "INSERT INTO likes (user_id, product_id, created_at) VALUES (?, ?, ?)";
        List<Object[]> rows = new ArrayList<>(CHUNK_SIZE);
        for (int i = 1; i <= products; i++) {
            int likeCount = likeCountOf(i, users);
            for (int k = 0; k < likeCount; k++) {
                rows.add(new Object[] {likerOf(i, k, users), i, SEEDED_AT});
                if (rows.size() == CHUNK_SIZE) {
                    flush(sql, rows);
                }
            }
        }
        flush(sql, rows);
    }

    private void insert(String sql, int count, IntFunction<Object[]> rowOf) {
        List<Object[]> rows = new ArrayList<>(CHUNK_SIZE);
        for (int i = 1; i <= count; i++) {
            rows.add(rowOf.apply(i));
            if (rows.size() == CHUNK_SIZE) {
                flush(sql, rows);
            }
        }
        flush(sql, rows);
    }

    private void flush(String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, rows);
            rows.clear();
        }
    }
}
