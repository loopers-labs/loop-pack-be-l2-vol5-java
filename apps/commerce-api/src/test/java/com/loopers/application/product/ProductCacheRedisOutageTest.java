package com.loopers.application.product;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.application.like.LikeFacade;
import com.loopers.config.cache.ProductCacheRedisConfig;
import com.loopers.infrastructure.product.RedisProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.SqlCounter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 중단·재시작·응답 없음 장면. 이 클래스만 쓰는 전용 Redis 컨테이너를 고정 호스트 포트로 띄워
 * 공용 테스트 컨테이너와 다른 테스트 클래스에 영향을 주지 않는다. 재시작해도 포트가 같아 연결 설정이 그대로 유효하다.
 * 정리는 @AfterAll 에서 이 컨테이너만 멈춘다 (버리는 컨테이너라 FLUSHALL 불필요).
 */
@SpringBootTest
@Sql("/sql/product-perf-seed.sql")
class ProductCacheRedisOutageTest {

    private static final int DEDICATED_PORT = findFreePort();

    @SuppressWarnings({"deprecation", "resource"})
    private static final GenericContainer<?> DEDICATED_REDIS = new FixedHostPortGenericContainer<>("redis:7.0")
        .withFixedExposedPort(DEDICATED_PORT, 6379);

    static {
        DEDICATED_REDIS.start();
    }

    @DynamicPropertySource
    static void pointRedisToDedicatedContainer(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", DEDICATED_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> DEDICATED_PORT);
        registry.add("datasource.redis.replicas[0].host", DEDICATED_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> DEDICATED_PORT);
    }

    @AfterAll
    static void stopDedicatedContainer() {
        DEDICATED_REDIS.stop();
    }

    private static final Long FIRST_PRODUCT_ID = 5406L;
    private static final long NEW_PRICE = 99_999L;
    private static final String CACHE_KEY = ProductQueryFixture.CACHE_KEY;
    private static final Duration SINGLE_QUERY_LIMIT = Duration.ofSeconds(1);
    private static final Duration RECONNECT_LIMIT = Duration.ofSeconds(15);

    private final ProductFacade productFacade;
    private final LikeFacade likeFacade;
    private final RedisTemplate<String, String> cacheRedisTemplate;
    private final MeterRegistry meterRegistry;
    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private SqlCounter sqlCounter;
    private ListAppender<ILoggingEvent> cacheLogs;

    @Autowired
    public ProductCacheRedisOutageTest(
        ProductFacade productFacade,
        LikeFacade likeFacade,
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
        MeterRegistry meterRegistry,
        EntityManagerFactory entityManagerFactory,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.productFacade = productFacade;
        this.likeFacade = likeFacade;
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.meterRegistry = meterRegistry;
        this.entityManagerFactory = entityManagerFactory;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        sqlCounter = new SqlCounter(entityManagerFactory);
        cacheLogs = new ListAppender<>();
        cacheLogs.start();
        cacheLogger().addAppender(cacheLogs);
    }

    @AfterEach
    void tearDown() {
        cacheLogger().detachAppender(cacheLogs);
        sqlCounter.disable();
        databaseCleanUp.truncateAllTables();
        // 어떤 테스트가 중단 상태에서 실패해도 다음 테스트가 꺼진 Redis 를 물려받지 않게 한다
        if (!DEDICATED_REDIS.isRunning()) {
            DEDICATED_REDIS.start();
        }
        if (DEDICATED_REDIS.isRunning()) {
            Set<String> keys = cacheRedisTemplate.keys("products:*");
            if (keys != null && !keys.isEmpty()) {
                cacheRedisTemplate.delete(keys);
            }
        }
    }

    private static Logger cacheLogger() {
        return (Logger) LoggerFactory.getLogger(RedisProductListCache.class);
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("빈 포트를 찾지 못했다", e);
        }
    }

    private List<ProductInfo> queryFixturePage() {
        return productFacade.getProducts(
            ProductQueryFixture.BRAND_ID, ProductQueryFixture.SORT, ProductQueryFixture.PAGE, ProductQueryFixture.SIZE);
    }

    /** 목록 조회 1회를 실행하고 걸린 시간(ms)을 돌려준다. 결과는 out 에 담는다. */
    private long timedQuery(List<ProductInfo> out) {
        long start = System.nanoTime();
        out.addAll(queryFixturePage());
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private long priceInDb(Long productId) {
        return jdbcTemplate.queryForObject("SELECT amount FROM products WHERE id = ?", Long.class, productId);
    }

    private List<String> warnMessages() {
        return cacheLogs.list.stream()
            .filter(event -> event.getLevel().toString().equals("WARN"))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    /** 재시작한 Redis 에 캐시 연결이 다시 붙을 때까지 유한하게 기다리고, 걸린 시간(ms)을 돌려준다. */
    private long awaitReconnect() throws InterruptedException {
        long start = System.nanoTime();
        long deadline = start + RECONNECT_LIMIT.toNanos();
        while (true) {
            try {
                cacheRedisTemplate.hasKey("reconnect-probe");
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Redis 재시작 뒤 " + RECONNECT_LIMIT + " 안에 재연결되지 않았다", e);
                }
                Thread.sleep(100);
            }
        }
    }

    /**
     * 첫 상품(5406)이 빠졌을 때의 기대 목록: 기존 2~20번째 + 21번째.
     */
    private List<ProductInfo> expectedWithoutFirst() {
        List<ProductInfo> expected = new ArrayList<>(ProductQueryFixture.EXPECTED_PAGE_0.subList(1, 20));
        expected.add(ProductQueryFixture.EXPECTED_21ST);
        return expected;
    }

    @DisplayName("Redis 중단 중 조회는 DB 로 우회해 정상 응답하고 쓰기는 commit 대로 성공하며, 재시작 뒤 캐시가 DB 와 같은 내용으로 다시 채워진다.")
    @Test
    void fallsBackDuringOutage_andRefillsAfterRestart() throws InterruptedException {
        // arrange: miss → hit 로 캐시가 쓰이는 상태
        queryFixturePage();
        sqlCounter.reset();
        assertThat(queryFixturePage()).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
        assertThat(sqlCounter.count()).as("hit 상태 (재고 조회 1개)").isEqualTo(1);
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();

        // act 1: Redis 중단
        DEDICATED_REDIS.stop();
        assertThat(DEDICATED_REDIS.isRunning()).isFalse();

        List<ProductInfo> duringOutage = new ArrayList<>();
        CacheCounterSnapshot beforeOutageQuery = CacheCounterSnapshot.take(meterRegistry);
        long outageQueryMs = timedQuery(duringOutage);
        assertThat(beforeOutageQuery.deltaTo(CacheCounterSnapshot.take(meterRegistry)))
            .as("중단 중 조회 1회: find_error 1, miss 1, save_error 1, hit 0")
            .isEqualTo(CacheCounterSnapshot.only("find_error", "miss", "save_error"));
        ProductInfo updated = productFacade.updateProduct(FIRST_PRODUCT_ID, "중단 중 수정", NEW_PRICE);
        boolean liked = likeFacade.like(15L, 6006L); // 브랜드 7 이지만 첫 페이지 밖(가격 22,322)인 상품
        List<String> warnsDuringOutage = warnMessages();

        // assert 1: 조회는 DB 결과 그대로, 쓰기는 성공 응답 + DB 반영, 캐시 삭제 실패 로그
        System.out.printf("[장면 D] Redis 중단 중 조회 1회 소요: %d ms%n", outageQueryMs);
        assertThat(duringOutage).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
        assertThat(outageQueryMs).as("중단 중 조회 1회 소요(ms)").isLessThan(SINGLE_QUERY_LIMIT.toMillis());
        assertThat(updated.price()).isEqualTo(NEW_PRICE);
        assertThat(priceInDb(FIRST_PRODUCT_ID)).isEqualTo(NEW_PRICE);
        assertThat(liked).isTrue();
        assertThat(warnsDuringOutage).anyMatch(message -> message.contains("캐시 조회 실패"));
        assertThat(warnsDuringOutage).anyMatch(message -> message.contains("캐시 저장 실패"));
        assertThat(warnsDuringOutage.stream().filter(message -> message.contains("캐시 삭제 실패")).count())
            .as("가격 수정·좋아요 두 쓰기의 삭제 실패 로그").isEqualTo(2);

        // act 2: 같은 포트로 재시작 → 재연결 대기
        DEDICATED_REDIS.start();
        long reconnectMs = awaitReconnect();
        System.out.printf("[장면 D] Redis 재시작 뒤 재연결까지: %d ms%n", reconnectMs);

        // 재시작 직후 옛 키가 남아 있는지 그대로 기록한다
        Boolean staleKeyExists = cacheRedisTemplate.hasKey(CACHE_KEY);
        Long staleKeyTtl = cacheRedisTemplate.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        System.out.printf("[장면 D] 재시작 직후 옛 키 존재=%s, TTL=%s%n", staleKeyExists, staleKeyTtl);
        List<ProductInfo> afterRestart = queryFixturePage();
        System.out.printf("[장면 D] 재시작 직후 조회의 5406 가격: %s (DB=%d)%n",
            afterRestart.stream().filter(info -> info.productId().equals(FIRST_PRODUCT_ID)).map(ProductInfo::price).findFirst()
                .map(String::valueOf).orElse("첫 페이지에 없음"), priceInDb(FIRST_PRODUCT_ID));

        // assert 2: 재채움 결과가 DB(중단 중 바뀐 가격) 와 일치하고 키가 다시 생긴다
        assertThat(afterRestart).containsExactlyElementsOf(expectedWithoutFirst());
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();
        sqlCounter.reset();
        assertThat(queryFixturePage()).containsExactlyElementsOf(expectedWithoutFirst());
        assertThat(sqlCounter.count()).as("재채움 뒤 hit").isEqualTo(1);
    }

    /** 컨테이너를 지우지 않고 멈춘다 (docker stop). Testcontainers 의 stop() 과 달리 데이터 디렉터리가 남는다. */
    private void dockerStop() {
        DEDICATED_REDIS.getDockerClient().stopContainerCmd(DEDICATED_REDIS.getContainerId()).exec();
    }

    /** 멈춘 같은 컨테이너를 다시 시작한다 (docker start). */
    private void dockerStart() {
        DEDICATED_REDIS.getDockerClient().startContainerCmd(DEDICATED_REDIS.getContainerId()).exec();
    }

    /** TTL 만료를 테스트에서 재현한다. 키에 1ms 만료를 걸고 사라질 때까지 짧게 기다린다 (상한 1초). */
    private void expireCacheKey() throws InterruptedException {
        cacheRedisTemplate.expire(CACHE_KEY, Duration.ofMillis(1));
        long deadline = System.nanoTime() + SINGLE_QUERY_LIMIT.toNanos();
        while (Boolean.TRUE.equals(cacheRedisTemplate.hasKey(CACHE_KEY))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("캐시 키가 " + SINGLE_QUERY_LIMIT + " 안에 만료되지 않았다");
            }
            Thread.sleep(10);
        }
    }

    @DisplayName("동일 컨테이너 재시작(docker stop/start). 중단 중 가격이 바뀌었을 때 옛 키가 되살아나는지와 보이는 가격을 기록하고, 만료 뒤 DB 와 일치한다.")
    @Test
    void recordsStaleKeyAfterSameContainerRestart_whenPriceChangedDuringOutage() throws InterruptedException {
        // arrange
        queryFixturePage();
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();

        // act: 지우지 않고 멈춤 → 중단 중 가격 수정(캐시 삭제 실패) → 같은 컨테이너 시작 → 재연결 대기
        dockerStop();
        productFacade.updateProduct(FIRST_PRODUCT_ID, "중단 중 수정", NEW_PRICE);
        assertThat(warnMessages()).anyMatch(message -> message.contains("캐시 삭제 실패"));
        dockerStart();
        long reconnectMs = awaitReconnect();

        Boolean staleKeyExists = cacheRedisTemplate.hasKey(CACHE_KEY);
        Long staleKeyTtl = cacheRedisTemplate.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        List<ProductInfo> afterRestart = queryFixturePage();
        String shownPrice = afterRestart.stream()
            .filter(info -> info.productId().equals(FIRST_PRODUCT_ID))
            .map(info -> String.valueOf(info.price()))
            .findFirst()
            .orElse("첫 페이지에 없음(DB 가격 반영)");
        System.out.printf("[장면 D-동일재시작] 재연결 %d ms, 옛 키 존재=%s, TTL=%s초, 재시작 직후 5406 가격=%s, DB=%d%n",
            reconnectMs, staleKeyExists, staleKeyTtl, shownPrice, priceInDb(FIRST_PRODUCT_ID));

        // assert: 옛 키가 되살아났으면 정책대로 옛 가격(122)이 보이고, 아니면 DB 가격이 반영된다
        if (Boolean.TRUE.equals(staleKeyExists)) {
            assertThat(staleKeyTtl).isGreaterThan(0L).isLessThanOrEqualTo(30L);
            assertThat(afterRestart).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
        } else {
            assertThat(afterRestart).containsExactlyElementsOf(expectedWithoutFirst());
        }

        // act: 만료 뒤 복구
        expireCacheKey();

        // assert
        assertThat(queryFixturePage()).containsExactlyElementsOf(expectedWithoutFirst());
    }

    @DisplayName("동일 컨테이너 재시작. 중단 중 삭제된 상품은 옛 키가 되살아나도 조회 결과에 없다.")
    @Test
    void hidesDeletedProductAfterSameContainerRestart() throws InterruptedException {
        // arrange
        queryFixturePage();
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();

        // act
        dockerStop();
        productFacade.deleteProduct(FIRST_PRODUCT_ID);
        dockerStart();
        awaitReconnect();

        Boolean staleKeyExists = cacheRedisTemplate.hasKey(CACHE_KEY);
        List<ProductInfo> afterRestart = queryFixturePage();
        System.out.printf("[장면 D-동일재시작] 삭제 뒤 옛 키 존재=%s, 결과에 5406 포함=%s%n",
            staleKeyExists, afterRestart.stream().anyMatch(info -> info.productId().equals(FIRST_PRODUCT_ID)));

        // assert: 되살아났든 아니든 삭제 상품은 보이지 않는다
        assertThat(afterRestart).containsExactlyElementsOf(expectedWithoutFirst());
    }

    @DisplayName("보조. Redis 가 응답하지 않으면(pause) 명령 타임아웃 안에 DB 로 우회해 정상 응답한다.")
    @Test
    void fallsBackWithinCommandTimeout_whenRedisIsPaused() {
        // arrange
        queryFixturePage();
        assertThat(cacheRedisTemplate.hasKey(CACHE_KEY)).isTrue();

        // act
        DEDICATED_REDIS.getDockerClient().pauseContainerCmd(DEDICATED_REDIS.getContainerId()).exec();
        List<ProductInfo> whilePaused = new ArrayList<>();
        long pausedQueryMs;
        try {
            pausedQueryMs = timedQuery(whilePaused);
        } finally {
            DEDICATED_REDIS.getDockerClient().unpauseContainerCmd(DEDICATED_REDIS.getContainerId()).exec();
        }

        // assert
        System.out.printf("[장면 D] Redis 응답 없음(pause) 중 조회 1회 소요: %d ms%n", pausedQueryMs);
        assertThat(whilePaused).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
        assertThat(pausedQueryMs).as("응답 없음 중 조회 1회 소요(ms)").isLessThan(SINGLE_QUERY_LIMIT.toMillis());
        assertThat(warnMessages()).anyMatch(message -> message.contains("캐시 조회 실패"));
    }
}
