package com.loopers.application.product;

import com.loopers.application.product.cache.CachedProductListInfo;
import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.domain.product.ProductSort;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

/**
 * 상품 목록 캐시 도입 후의 요청 지연을 경로별(DB-only·miss·hit)로 잰다. {@code ProductQueryLatencyBenchmarkTest} 와 같은
 * {@code sql/perf-seed.sql} 을 Testcontainers MySQL 에 적재하고, Redis 왕복·JSON 변환·결과 조립까지 포함한 {@code ProductFacade}
 * 호출 시간을 순차로 잰다(준비 20회, 측정 100회, 3라운드). HTTP 지연은 아니다.
 *
 * <p>측정용이라 환경변수 {@code PERF_CACHE_BENCHMARK=true} 일 때만 실행하고, 평소 {@code check} 에서는 건너뛴다(skip 으로 표시된다).
 *
 * <pre>
 * PERF_CACHE_BENCHMARK=true ./gradlew :apps:commerce-api:test --tests '*ProductCacheLatencyBenchmarkTest' -i
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "PERF_CACHE_BENCHMARK", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductCacheLatencyBenchmarkTest {

    private static final int WARMUP = 20;
    private static final int MEASURE = 100;
    private static final int ROUNDS = 3;
    private static final int OUTAGE_SAMPLES = 10;

    // DB-only 경로를 만들 때만 캐시를 "항상 없음·저장 안 함"으로 바꾸고, 나머지 경로는 실제 Redis 구현 그대로 쓴다.
    @MockitoSpyBean
    private ProductListCache productListCache;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeAll
    void seed() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/perf-seed.sql"));
        }
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Page<ProductListItemInfo> request(Long brandId, String sort) {
        return productFacade.getList(brandId, sort, 0, 20);
    }

    @DisplayName("요청별로 DB-only·miss·hit 의 지연과 SQL 수를 기록하고, 세 경로의 결과가 같은지 확인한다.")
    @Test
    void measureLatencyByPath() {
        for (Object[] target : List.of(
            new Object[]{"A brand7 price_asc", 7L, "price_asc"},
            new Object[]{"C all latest", null, "latest"}
        )) {
            String label = (String) target[0];
            Long brandId = (Long) target[1];
            String sort = (String) target[2];

            Page<ProductListItemInfo> dbOnly = withoutCache(() -> request(brandId, sort));
            productListCache.evictAll();
            Page<ProductListItemInfo> miss = request(brandId, sort);
            Page<ProductListItemInfo> hit = request(brandId, sort);
            assertThat(miss.getContent()).isEqualTo(dbOnly.getContent()).hasSize(20);
            assertThat(hit.getContent()).isEqualTo(dbOnly.getContent());
            assertThat(hit.getTotalElements()).isEqualTo(dbOnly.getTotalElements());

            for (int r = 0; r < ROUNDS; r++) {
                withoutCache(() -> {
                    report(label, "db-only", round(() -> request(brandId, sort), () -> { }));
                    return null;
                });
                report(label, "miss", round(() -> request(brandId, sort), productListCache::evictAll));
                request(brandId, sort);
                report(label, "hit", round(() -> request(brandId, sort), () -> { }));
            }
        }
    }

    @DisplayName("영역 비우기(SCAN + UNLINK)의 시간을 키 수별로 기록한다.")
    @Test
    void measureEvictAll() {
        CachedProductListInfo snapshot = CachedProductListInfo.from(request(7L, "price_asc"));
        for (int keys : List.of(100, 1_000, 10_000)) {
            long[] samples = new long[5];
            for (int i = 0; i < samples.length; i++) {
                IntStream.range(0, keys).forEach(page ->
                    productListCache.save(new ProductListQuery(7L, ProductSort.PRICE_ASC, page, 20), snapshot));
                long startedAt = System.nanoTime();
                productListCache.evictAll();
                samples[i] = System.nanoTime() - startedAt;
            }
            Arrays.sort(samples);
            System.out.printf(Locale.ROOT, "PERF>>> evictAll keys=%d samples=5 p50=%.3f max=%.3f (ms)%n",
                keys, ms(samples[2]), ms(samples[4]));
        }
    }

    @DisplayName("Redis 가 응답하지 않을 때(pause)의 요청 지연을 기록한다.")
    @Test
    void measureRedisOutage() {
        GenericContainer<?> redis = RedisTestContainersConfig.container();
        Page<ProductListItemInfo> expected = request(7L, "price_asc");
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            long[] samples = new long[OUTAGE_SAMPLES];
            for (int i = 0; i < OUTAGE_SAMPLES; i++) {
                long startedAt = System.nanoTime();
                Page<ProductListItemInfo> page = request(7L, "price_asc");
                samples[i] = System.nanoTime() - startedAt;
                assertThat(page.getContent()).isEqualTo(expected.getContent());
            }
            Arrays.sort(samples);
            System.out.printf(Locale.ROOT, "PERF>>> A brand7 price_asc redis-paused samples=%d p50=%.3f max=%.3f (ms)%n",
                OUTAGE_SAMPLES, ms(samples[OUTAGE_SAMPLES / 2]), ms(samples[OUTAGE_SAMPLES - 1]));
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    private <T> T withoutCache(Supplier<T> action) {
        doReturn(Optional.empty()).when(productListCache).find(any());
        doNothing().when(productListCache).save(any(), any());
        try {
            return action.get();
        } finally {
            Mockito.reset(productListCache);
        }
    }

    private record Result(long[] sortedNanos, double statementsPerRequest) {
    }

    // beforeEach 는 측정 밖에서 매번 실행한다(miss 를 만들려고 영역을 비우는 것 등).
    private Result round(Runnable call, Runnable beforeEach) {
        for (int i = 0; i < WARMUP; i++) {
            beforeEach.run();
            call.run();
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long[] samples = new long[MEASURE];
        long statements = 0;
        for (int i = 0; i < MEASURE; i++) {
            beforeEach.run();
            statistics.clear();
            long startedAt = System.nanoTime();
            call.run();
            samples[i] = System.nanoTime() - startedAt;
            statements += statistics.getPrepareStatementCount();
        }
        Arrays.sort(samples);
        return new Result(samples, (double) statements / MEASURE);
    }

    private static void report(String label, String path, Result result) {
        long[] s = result.sortedNanos();
        System.out.printf(Locale.ROOT, "PERF>>> %s %-7s sql/req=%.1f p50=%.3f p95=%.3f max=%.3f (ms)%n",
            label, path, result.statementsPerRequest(), ms(s[49]), ms(s[94]), ms(s[99]));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
