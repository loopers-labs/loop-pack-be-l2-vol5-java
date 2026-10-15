package com.loopers.infrastructure.cache;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 캐시 전용 Redis 연결 — 공용 RedisConfig(modules/redis)는 그대로 두고, 접속 정보(RedisProperties)만 재사용한다.
 *
 * <p>캐시는 실패해도 DB로 대신할 수 있으므로 공격적으로 설정한다(docs/week4/design.md 8번 섹션):
 * 명령 타임아웃 50ms(Lettuce 기본 60초), 연결이 끊기면 대기열에 쌓지 않고 즉시 거절.
 * 연결 수립(TCP 연결 + 초기 handshake)은 명령과 따로 1초로 둔다 — Lettuce는 RedisURI 타임아웃(= commandTimeout)을
 * handshake에도 쓰므로 50ms를 그대로 주면 Windows Docker 환경에서 첫 연결부터 실패했다. 연결은 앱 시작·재연결 때만 맺는다.
 * 이 설정은 Redis를 정답 저장소로 쓰는 용도(락·카운터)에는 위험하므로 캐시 연결에만 둔다.
 * 읽기는 replica 우선(기존 REPLICA_PREFERRED와 같음), 쓰기·DEL은 Lettuce가 master로 보낸다.
 */
@Configuration
public class CacheRedisConfig {

    public static final String CACHE_REDIS_TEMPLATE = "cacheRedisTemplate";
    private static final String CACHE_CONNECTION_FACTORY = "cacheRedisConnectionFactory";

    @Qualifier(CACHE_CONNECTION_FACTORY)
    @Bean(CACHE_CONNECTION_FACTORY)
    public LettuceConnectionFactory cacheRedisConnectionFactory(
        RedisProperties redisProperties,
        @Value("${cache.redis.command-timeout:50ms}") Duration commandTimeout,
        @Value("${cache.redis.connect-timeout:1s}") Duration connectTimeout
    ) {
        ClientOptions clientOptions = ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
            .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
            .build();
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .commandTimeout(connectTimeout)
            .clientOptions(clientOptions)
            .build();

        RedisNodeInfo master = redisProperties.master();
        RedisStaticMasterReplicaConfiguration serverConfig =
            new RedisStaticMasterReplicaConfiguration(master.host(), master.port());
        serverConfig.setDatabase(redisProperties.database());
        for (RedisNodeInfo replica : redisProperties.replicas()) {
            serverConfig.addNode(replica.host(), replica.port());
        }
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Qualifier(CACHE_REDIS_TEMPLATE)
    @Bean(CACHE_REDIS_TEMPLATE)
    public RedisTemplate<String, String> cacheRedisTemplate(
        @Qualifier(CACHE_CONNECTION_FACTORY) LettuceConnectionFactory connectionFactory
    ) {
        // StringRedisTemplate 타입으로 등록하면 Boot 자동 구성의 stringRedisTemplate 자리를 차지하므로 RedisTemplate으로 둔다.
        RedisTemplate<String, String> template = new RedisTemplate<>();
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
