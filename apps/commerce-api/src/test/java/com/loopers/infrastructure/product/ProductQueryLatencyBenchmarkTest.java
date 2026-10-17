package com.loopers.infrastructure.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 목록 요청 한 번이 보내는 SQL 의 반복 지연을 잰다(준비 실행 20회, 측정 100회, 순차). 앱이 보내는 문장을 같은 순서로
 * 실행하므로 "SQL 합계"는 요청의 DB 시간이며, HTTP 지연이 아니다. 브랜드 없는 {@code likes_desc} 는 집계 테이블에서 상품 id 를
 * 먼저 읽고 상품을 조회하는 2단계라 "list" 에 두 문장의 시간을 합친다.
 *
 * <p>로컬 docker MySQL 의 {@code loopers} 에 {@code sql/perf-seed.sql} 을 적재한 상태를 대상으로 하므로 환경변수
 * {@code PERF_JDBC_URL} 이 있을 때만 실행하고, 평소 {@code check} 에서는 건너뛴다(skip 으로 표시된다). SELECT 만 실행한다.
 * 적재와 체크섬 비교 절차는 {@code docs/design/week4/product-list-baseline.md} 에 있다.
 *
 * <pre>
 * PERF_JDBC_URL='jdbc:mysql://localhost:3306/loopers' PERF_JDBC_USER=application PERF_JDBC_PASSWORD=application \
 *   ./gradlew :apps:commerce-api:test --tests '*ProductQueryLatencyBenchmarkTest' -i
 * </pre>
 *
 * <p>SQL 문장은 앱이 실제로 보내는 문장(문서 3장)에서 파라미터(브랜드 7, limit 20)만 값으로 바꾼 것이다.
 * 쿼리를 바꾸면 이 문장도 앱과 같게 갱신한다.
 */
@EnabledIfEnvironmentVariable(named = "PERF_JDBC_URL", matches = ".+")
class ProductQueryLatencyBenchmarkTest {

    private static final int WARMUP = 20;
    private static final int MEASURE = 100;

    private static final String PRODUCT_COLUMNS =
        "p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.price,p1_0.stock,p1_0.updated_at";

    // brand 는 SQL 리터럴이다. 브랜드 필터가 없으면 앱이 null 파라미터를 보내므로 "NULL" 을 쓴다.
    private static String priceAscList(String brand) {
        return "select " + PRODUCT_COLUMNS + " from products p1_0 where p1_0.deleted_at is null "
            + "and (" + brand + " is null or p1_0.brand_id=" + brand + ") order by p1_0.price,p1_0.id desc limit 20";
    }

    // 브랜드를 지정한 likes_desc: 상품과 좋아요 집계를 left join 해 집계 내림차순으로 정렬한다.
    private static String likesDescListOfBrand(String brand) {
        return "select " + PRODUCT_COLUMNS + " from products p1_0 "
            + "left join product_like_counts c1_0 on c1_0.product_id=p1_0.id "
            + "where p1_0.deleted_at is null and p1_0.brand_id=" + brand + " "
            + "order by coalesce(c1_0.like_count,0) desc,p1_0.id desc limit 20";
    }

    // 브랜드 없는 likes_desc 1단계: 집계 테이블에서 페이지의 상품 id 만 읽는다.
    private static final String LIKES_DESC_IDS =
        "select c1_0.product_id from product_like_counts c1_0 order by c1_0.like_count desc,c1_0.product_id desc limit 20";

    private static String count(String brand) {
        return "select count(p1_0.id) from products p1_0 "
            + "where p1_0.deleted_at is null and (" + brand + " is null or p1_0.brand_id=" + brand + ")";
    }

    @Test
    @DisplayName("브랜드 7 가격 낮은 순 첫 페이지 요청의 SQL 지연을 기록한다 (과제 필수 측정 대상)")
    void measurePriceAscRequest() throws SQLException {
        measure("price_asc brand7", priceAscList("7"), count("7"));
    }

    @Test
    @DisplayName("브랜드 없이 가격 낮은 순 첫 페이지 요청의 SQL 지연을 기록한다 (전역 가격순 인덱스 후보 비교용)")
    void measurePriceAscWithoutBrandRequest() throws SQLException {
        measure("price_asc all", priceAscList("NULL"), count("NULL"));
    }

    @Test
    @DisplayName("브랜드 7 좋아요 많은 순 첫 페이지 요청의 SQL 지연을 기록한다 (비교용)")
    void measureLikesDescRequest() throws SQLException {
        measure("likes_desc brand7", likesDescListOfBrand("7"), count("7"));
    }

    @Test
    @DisplayName("브랜드 없이 좋아요 많은 순 첫 페이지 요청의 SQL 지연을 기록한다 (병목 기준선)")
    void measureLikesDescWithoutBrandRequest() throws SQLException {
        measure("likes_desc all", null, count("NULL"));
    }

    private void measure(String label, String listSql, String countSql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            System.getenv("PERF_JDBC_URL"),
            System.getenv().getOrDefault("PERF_JDBC_USER", "root"),
            System.getenv().getOrDefault("PERF_JDBC_PASSWORD", "root"));
            Statement statement = connection.createStatement()) {

            for (int i = 0; i < WARMUP; i++) {
                runRequest(statement, listSql, countSql);
            }
            Map<String, long[]> samples = new LinkedHashMap<>();
            for (String step : List.of("list", "count", "brands", "likes", "total")) {
                samples.put(step, new long[MEASURE]);
            }
            int rows = 0;
            for (int i = 0; i < MEASURE; i++) {
                Map<String, Long> once = runRequest(statement, listSql, countSql);
                for (Map.Entry<String, long[]> entry : samples.entrySet()) {
                    entry.getValue()[i] = once.get(entry.getKey());
                }
                rows = once.get("rows").intValue();
            }
            assertThat(rows).isEqualTo(20);
            System.out.printf("PERF>>> %s warmup=%d measure=%d (ms)%n", label, WARMUP, MEASURE);
            for (Map.Entry<String, long[]> entry : samples.entrySet()) {
                long[] sorted = entry.getValue().clone();
                Arrays.sort(sorted);
                System.out.printf(Locale.ROOT, "PERF>>> %s %-6s p50=%.3f p95=%.3f max=%.3f%n",
                    label, entry.getKey(), ms(sorted[49]), ms(sorted[94]), ms(sorted[99]));
            }
        }
    }

    // 앱과 같은 순서로 문장을 실행한다: 목록, 총 개수, 목록의 브랜드, 목록의 좋아요 수(집계 테이블 PK 조회).
    private Map<String, Long> runRequest(Statement statement, String listSql, String countSql) throws SQLException {
        Map<String, Long> nanos = new LinkedHashMap<>();
        long start = System.nanoTime();

        long t = System.nanoTime();
        List<Long> productIds = new ArrayList<>();
        List<Long> brandIds = new ArrayList<>();
        String effectiveListSql = listSql;
        if (effectiveListSql == null) {
            // 브랜드 없는 likes_desc: 집계 테이블에서 페이지의 상품 id 를 읽고, 그 id 로 상품을 조회한다.
            List<Long> pageIds = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(LIKES_DESC_IDS)) {
                while (rs.next()) {
                    pageIds.add(rs.getLong(1));
                }
            }
            effectiveListSql = "select " + PRODUCT_COLUMNS + " from products p1_0 where p1_0.id in (" + join(pageIds)
                + ") and p1_0.deleted_at is null";
        }
        try (ResultSet rs = statement.executeQuery(effectiveListSql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    rs.getObject(c);
                }
                productIds.add(rs.getLong("id"));
                brandIds.add(rs.getLong("brand_id"));
            }
        }
        nanos.put("list", System.nanoTime() - t);

        t = System.nanoTime();
        drain(statement, countSql);
        nanos.put("count", System.nanoTime() - t);

        t = System.nanoTime();
        drain(statement, "select b1_0.id,b1_0.created_at,b1_0.deleted_at,b1_0.name,b1_0.updated_at from brands b1_0 "
            + "where b1_0.id in (" + join(brandIds.stream().distinct().toList()) + ") and b1_0.deleted_at is null");
        nanos.put("brands", System.nanoTime() - t);

        t = System.nanoTime();
        drain(statement, "select c1_0.product_id,c1_0.like_count from product_like_counts c1_0 "
            + "where c1_0.product_id in (" + join(productIds) + ") and c1_0.like_count>0");
        nanos.put("likes", System.nanoTime() - t);

        nanos.put("total", System.nanoTime() - start);
        nanos.put("rows", (long) productIds.size());
        return nanos;
    }

    private static void drain(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    rs.getObject(c);
                }
            }
        }
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
