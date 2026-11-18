package com.loopers;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.config.PurchaseActivityClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

// E2E의 batch 컨텍스트(9주차 4단계). 주간 랭킹 job·확장 job·런처(com.loopers.batch.job.ranking)와 공통 모듈 설정만 스캔한다.
// @Configuration을 붙이지 않아 api 컨텍스트의 컴포넌트 스캔에 잡히지 않는다. 스키마(api 엔티티)는 api 컨텍스트가 만들므로 ddl-auto는 none이고,
// Batch metadata와 랭킹 테이블은 batch SQL(sql/create-ranking-tables.sql)을 이 컨텍스트가 시작할 때 실행해 만든다.
// application.yml은 api 쪽이 먼저 읽히므로 batch 설정은 명령행 인자로 준다. 대역은 공개 직전 실패 hook과 고정 시각뿐이다.
@EnableAutoConfiguration
@ComponentScan(
    basePackages = {"com.loopers.batch.job.ranking", "com.loopers.config", "com.loopers.confg"},
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PurchaseActivityClockConfig.class)
)
@Import(E2eBatchContext.Doubles.class)
public class E2eBatchContext {
    public static final Instant FROZEN_AT = Instant.parse("2026-11-02T01:00:00Z");

    public static ConfigurableApplicationContext start(String jdbcUrl, String username, String password) {
        return new SpringApplicationBuilder(E2eBatchContext.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.application.name=commerce-batch-e2e",
                // api application.yml의 web-application-type(servlet)이 builder 설정을 덮으므로 인자로 끈다.
                "--spring.main.web-application-type=none",
                "--spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
                "--spring.batch.job.enabled=false",
                "--spring.batch.jdbc.initialize-schema=always",
                "--spring.sql.init.mode=always",
                "--spring.sql.init.schema-locations=classpath:sql/create-ranking-tables.sql",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--commerce.ranking.chunk-size=2",
                "--commerce.ranking.size=150",
                "--datasource.mysql-jpa.main.jdbc-url=" + jdbcUrl,
                "--datasource.mysql-jpa.main.username=" + username,
                "--datasource.mysql-jpa.main.password=" + password);
    }

    public static class Doubles {
        // 고정 시각(frozen_at)을 테스트가 정한다.
        @Bean
        @Primary
        Clock e2eRankingClock() {
            return Clock.fixed(FROZEN_AT, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        HookedRankingRepository e2eRankingRepository(JdbcTemplate jdbcTemplate) {
            return new HookedRankingRepository(jdbcTemplate);
        }
    }

    // 운영 분기 없이 저장소를 감싸 "공개 직전 실패"를 한 번 주입한다. r2를 고정·계산까지만 끝내 공개 전 API를 보기 위한 장치다.
    public static class HookedRankingRepository extends WeeklyRankingRepository {
        public static final String PUBLISH_FAILURE = "주입된 pointer 공개 실패";

        private final AtomicBoolean failNextPublish = new AtomicBoolean();

        HookedRankingRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        public void failNextPublish() {
            failNextPublish.set(true);
        }

        public void disarm() {
            failNextPublish.set(false);
        }

        @Override
        public boolean publish(LocalDate periodStart, String snapshotId) {
            if (failNextPublish.getAndSet(false)) {
                throw new IllegalStateException(PUBLISH_FAILURE);
            }
            return super.publish(periodStart, snapshotId);
        }
    }
}
