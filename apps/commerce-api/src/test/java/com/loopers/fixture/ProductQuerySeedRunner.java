package com.loopers.fixture;

import com.loopers.config.jpa.JpaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ProductQuerySeedRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductQuerySeedRunner.class);

    private ProductQuerySeedRunner() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("seed 실행은 인자를 받지 않습니다.");
        }
        try (var context = new SpringApplicationBuilder(SeedConfiguration.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.profiles.active=local,product-query-seed",
                "--spring.main.web-application-type=none",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.show-sql=false",
                "--datasource.mysql-jpa.main.maximum-pool-size=2",
                "--datasource.mysql-jpa.main.minimum-idle=1"
            )) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            verifyTargetDatabase(jdbcTemplate);
            ProductQuerySeed.SeedResult seed = new ProductQuerySeed(jdbcTemplate).load();
            verifySeed(jdbcTemplate, seed);
            log.info("seed 검증 완료: database=loopers, brands=100, users=1000, products={}, likes={}, 측정 brandId={}",
                seed.productCount(), seed.likeCount(), seed.brandId(7));
        }
    }

    static void verifyTargetDatabase(JdbcTemplate jdbcTemplate) {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!"loopers".equals(database)) {
            throw new IllegalStateException("실행용 seed는 loopers DB에서만 허용합니다.");
        }
    }

    private static void verifySeed(JdbcTemplate jdbcTemplate, ProductQuerySeed.SeedResult seed) {
        verifyValue(jdbcTemplate, "SELECT COUNT(*) FROM brands", 100);
        verifyValue(jdbcTemplate, "SELECT COUNT(*) FROM users", 1_000);
        verifyValue(jdbcTemplate, "SELECT COUNT(*) FROM products", 1_000_000);
        verifyValue(jdbcTemplate, "SELECT COUNT(*) FROM likes", 5_194_998);
        verifyValue(jdbcTemplate, "SELECT SUM(price) FROM products", 50_099_500_000L);
        verifyValue(jdbcTemplate, "SELECT COUNT(*) FROM products WHERE deleted_at IS NOT NULL", 12_000);
        verifyValue(jdbcTemplate, "SELECT COUNT(*) FROM products WHERE brand_id = ?", 10_000, seed.brandId(7));
        verifyValue(jdbcTemplate, "SELECT SUM(price) FROM products WHERE brand_id = ?", 500_720_000, seed.brandId(7));
        verifyValue(jdbcTemplate, """
            SELECT COUNT(*) FROM (
                SELECT brand_id FROM products GROUP BY brand_id HAVING COUNT(*) <> 10000
            ) unbalanced_brands
            """, 0);
    }

    private static void verifyValue(JdbcTemplate jdbcTemplate, String sql, long expected, Object... args) {
        Long actual = jdbcTemplate.queryForObject(sql, Long.class, args);
        if (actual == null || actual != expected) {
            throw new IllegalStateException("seed 검증 실패: expected=" + expected + ", actual=" + actual + ", sql=" + sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = JpaConfig.class)
    @Profile("product-query-seed")
    static class SeedConfiguration {
    }
}
