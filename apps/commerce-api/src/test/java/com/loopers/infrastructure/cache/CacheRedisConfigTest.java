package com.loopers.infrastructure.cache;

import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class CacheRedisConfigTest {

    @Autowired
    @Qualifier("cacheRedisConnectionFactory")
    private LettuceConnectionFactory cacheConnectionFactory;

    @Autowired
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @Autowired
    private RedisTemplate<String, String> defaultRedisTemplate;

    @Autowired
    private RedisProperties redisProperties;

    @DisplayName("캐시 연결은 명령 타임아웃 50ms, 연결 수립 1초, 연결이 끊기면 즉시 거절로 설정된다.")
    @Test
    void cacheConnection_failsFast() {
        LettuceClientConfiguration clientConfig = cacheConnectionFactory.getClientConfiguration();
        ClientOptions clientOptions = clientConfig.getClientOptions().orElseThrow();
        TimeoutOptions.TimeoutSource commandTimeout = clientOptions.getTimeoutOptions().getSource();

        assertAll(
            () -> assertThat(TimeUnit.MILLISECONDS.convert(commandTimeout.getTimeout(null), commandTimeout.getTimeUnit()))
                .isEqualTo(50L),
            () -> assertThat(clientOptions.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(clientConfig.getCommandTimeout()).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(clientOptions.getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        );
    }

    @DisplayName("테스트는 개발용 로컬 Redis가 아니라 테스트 컨테이너 Redis에 붙는다.")
    @Test
    void testsUseTestContainerRedis() {
        assertThat(redisProperties.master().port())
            .isEqualTo(Integer.parseInt(System.getProperty("datasource.redis.master.port")));
    }

    @DisplayName("공용 기본 RedisTemplate은 캐시 연결이 아니다 — 캐시 설정이 다른 Redis 용도에 번지지 않는다.")
    @Test
    void defaultTemplate_isNotCacheConnection() {
        assertThat(defaultRedisTemplate).isNotSameAs(cacheRedisTemplate);
        assertThat(defaultRedisTemplate.getConnectionFactory()).isNotSameAs(cacheConnectionFactory);
    }

    @DisplayName("캐시 템플릿으로 저장한 값을 다시 읽을 수 있다.")
    @Test
    void cacheTemplate_roundTrip() {
        cacheRedisTemplate.opsForValue().set("cache-config-test", "ok", Duration.ofSeconds(5));

        assertThat(cacheRedisTemplate.opsForValue().get("cache-config-test")).isEqualTo("ok");

        cacheRedisTemplate.delete("cache-config-test");
    }
}
