package com.loopers.config;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

/**
 * 상품 목록 캐시 전용 Redis 연결 (4주차 docs/week4/cache-design.md 4.2).
 * 공통 모듈(modules/redis)은 batch · streamer 도 쓰므로 바꾸지 않고, 같은 datasource.redis 설정으로 commerce-api 안에서만 연결을 따로 둔다.
 * 명령 대기 한도는 100ms 이고, 연결이 끊긴 동안의 명령은 기다리지 않고 바로 실패한다.
 * 실패한 무효화를 다시 시도하는 일정(RedisProductListCache)도 여기서 켠다 (3.4)
 */
@Configuration
@EnableScheduling
public class ProductCacheRedisConfig {

    /** 범위 버전을 읽고 쓰는 연결. 복제 지연으로 옛 버전을 읽지 않도록 master 에서 읽는다 (4.1) */
    public static final String MASTER_TEMPLATE = "productCacheMasterRedisTemplate";
    /** 데이터 키를 읽는 연결. 키에 버전이 있어 replica 에서 읽어도 옛 값을 돌려주지 않는다 (4.1) */
    public static final String REPLICA_PREFERRED_TEMPLATE = "productCacheReplicaPreferredRedisTemplate";

    private static final String MASTER_CONNECTION = "productCacheMasterRedisConnection";
    private static final String REPLICA_PREFERRED_CONNECTION = "productCacheReplicaPreferredRedisConnection";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(100);

    @Qualifier(MASTER_CONNECTION)
    @Bean
    public LettuceConnectionFactory productCacheMasterRedisConnection(RedisProperties properties) {
        return connectionFactory(properties, ReadFrom.MASTER);
    }

    @Qualifier(REPLICA_PREFERRED_CONNECTION)
    @Bean
    public LettuceConnectionFactory productCacheReplicaPreferredRedisConnection(RedisProperties properties) {
        return connectionFactory(properties, ReadFrom.REPLICA_PREFERRED);
    }

    @Qualifier(MASTER_TEMPLATE)
    @Bean
    public StringRedisTemplate productCacheMasterRedisTemplate(@Qualifier(MASTER_CONNECTION) LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Qualifier(REPLICA_PREFERRED_TEMPLATE)
    @Bean
    public StringRedisTemplate productCacheReplicaPreferredRedisTemplate(
        @Qualifier(REPLICA_PREFERRED_CONNECTION) LettuceConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    private static LettuceConnectionFactory connectionFactory(RedisProperties properties, ReadFrom readFrom) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(readFrom)
            .commandTimeout(COMMAND_TIMEOUT)
            .clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build())
            .build();
        RedisNodeInfo master = properties.master();
        RedisStaticMasterReplicaConfiguration serverConfig = new RedisStaticMasterReplicaConfiguration(master.host(), master.port());
        serverConfig.setDatabase(properties.database());
        properties.replicas().forEach(replica -> serverConfig.addNode(replica.host(), replica.port()));
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }
}
