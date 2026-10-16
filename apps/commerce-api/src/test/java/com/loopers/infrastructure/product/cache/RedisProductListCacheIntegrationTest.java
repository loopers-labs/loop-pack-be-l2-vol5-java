package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.product.ProductListCacheException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RedisProductListCacheIntegrationTest {
    @Container
    private static final OwnedRedisContainer REDIS = new OwnedRedisContainer();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisProductListCache cache;
    private String namespace;

    @BeforeEach
    void setUp() {
        namespace = "commerce:integration:" + UUID.randomUUID() + ":products:v1";
        RedisProperties connection = new RedisProperties(0,
            new RedisNodeInfo(REDIS.getHost(), REDIS.getMappedPort(6379)), List.of());
        ProductListCacheConfiguration configuration = new ProductListCacheConfiguration();
        factory = configuration.connectionFactory(connection, properties(Duration.ofSeconds(60)));
        factory.afterPropertiesSet();
        redis = configuration.redisTemplate(factory);
        cache = new RedisProductListCache(redis, new ObjectMapper(), properties(Duration.ofSeconds(60)), registry);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (var ping = factory.getConnection()) {
                assertThat(ping.ping()).isEqualTo("PONG");
            }
        });
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
        registry.close();
    }

    @Test
    void realRedisMissFillHitRetainsOldRevisionWithoutReadingItForTheNextRevision() {
        assertThat(cache.find(42)).isEmpty();
        cache.store(RedisProductListCacheTest.snapshot());
        assertThat(cache.find(42)).contains(RedisProductListCacheTest.snapshot());
        assertThat(cache.find(43)).isEmpty();
        assertThat(redis.hasKey(key(42))).isTrue();
        assertThat(redis.getExpire(key(42), TimeUnit.MILLISECONDS)).isBetween(55_000L, 60_000L);
    }

    @Test
    void actualExpiryIsNotExtendedByHitsAndTheExpiredKeyCanBeRefilled() {
        RedisProductListCache shortLived = new RedisProductListCache(redis, new ObjectMapper(),
            properties(Duration.ofMillis(1500)), registry);
        shortLived.store(RedisProductListCacheTest.snapshot());
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(40)).untilAsserted(() ->
            assertThat(redis.getExpire(key(42), TimeUnit.MILLISECONDS)).isBetween(1L, 900L));

        assertThat(shortLived.find(42)).contains(RedisProductListCacheTest.snapshot());
        assertThat(redis.getExpire(key(42), TimeUnit.MILLISECONDS)).isBetween(1L, 900L);
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(40)).untilAsserted(() ->
            assertThat(shortLived.find(42)).isEmpty());
        shortLived.store(RedisProductListCacheTest.snapshot());
        assertThat(shortLived.find(42)).contains(RedisProductListCacheTest.snapshot());
    }

    @Test
    void corruptStoredJsonIsAMissAndCanBeReplacedWithAValidSnapshot() {
        redis.opsForValue().set(key(42), "{corrupt", Duration.ofSeconds(60));
        assertThat(cache.find(42)).isEmpty();
        assertThat(registry.get("commerce.product.list.cache").tags("operation", "lookup", "outcome", "invalid")
            .counter().count()).isEqualTo(1);
        cache.store(RedisProductListCacheTest.snapshot());
        assertThat(cache.find(42)).contains(RedisProductListCacheTest.snapshot());
    }

    @Test
    void ownedRedisCanStopAndRestartAtTheSameEndpointWithoutFlushingKeysForRecovery() {
        cache.store(RedisProductListCacheTest.snapshot());
        assertThat(cache.find(42)).contains(RedisProductListCacheTest.snapshot());
        int port = REDIS.actualPublishedPort();
        String id = REDIS.getContainerId();
        REDIS.getDockerClient().stopContainerCmd(id).withTimeout(1).exec();
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> cache.find(42)).isInstanceOf(ProductListCacheException.class);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMillis).as("bounded failure, not an API latency SLO").isLessThan(5000);
            assertThatThrownBy(() -> cache.store(RedisProductListCacheTest.snapshot()))
                .isInstanceOf(ProductListCacheException.class);
        } finally {
            REDIS.getDockerClient().startContainerCmd(id).exec();
        }
        assertThat(REDIS.actualPublishedPort()).isEqualTo(port);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .ignoreException(ProductListCacheException.class).untilAsserted(() -> {
                cache.store(RedisProductListCacheTest.snapshot());
                assertThat(cache.find(42)).contains(RedisProductListCacheTest.snapshot());
                assertThat(cache.find(43)).isEmpty();
            });
    }

    private ProductListCacheProperties properties(Duration ttl) {
        return new ProductListCacheProperties(true, namespace, ttl, Duration.ofMillis(100), Duration.ofMillis(100));
    }

    private String key(long revision) {
        return namespace + ":brand=all:sort=likes_desc:page=0:size=20:rev=" + revision;
    }
}
