package com.loopers.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * seed 프로파일로 띄울 때만 카탈로그를 채운다. 평소 실행과 기존 테스트에는 끼어들지 않는다.
 * 실행: ./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local,seed'
 * 크기 변경: --seed.catalog.products=1000000 (기본값은 과제 기준 10,000개)
 */
@Slf4j
@Profile("seed")
@Configuration
public class CatalogSeedRunner {

    @Bean
    ApplicationRunner catalogSeed(
        JdbcTemplate jdbcTemplate,
        @Value("${seed.catalog.brands:100}") int brands,
        @Value("${seed.catalog.products:10000}") int products,
        @Value("${seed.catalog.users:1000}") int users
    ) {
        return args -> {
            CatalogSeeder seeder = new CatalogSeeder(jdbcTemplate);
            if (!seeder.isEmpty()) {
                log.info("카탈로그 seed를 건너뜁니다 — 이미 데이터가 있습니다.");
                return;
            }
            long startedAt = System.currentTimeMillis();
            seeder.seed(brands, products, users);
            log.info("카탈로그 seed 완료 — 브랜드 {}, 상품 {}, 사용자 {} ({} ms)",
                brands, products, users, System.currentTimeMillis() - startedAt);
        };
    }
}
