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

import java.time.Duration;

/**
 * 쿠폰 접수 전용 Redis 연결. 공통 모듈은 batch · streamer 도 써서 바꾸지 않고 4주차 캐시처럼 따로 둔다 (8주차 D-161).
 * 순번 판정은 복제가 늦은 replica 를 보면 안 되므로 master 만 쓴다
 */
@Configuration
public class CouponRedisConfig {

    public static final String MASTER_TEMPLATE = "couponMasterRedisTemplate";

    private static final String MASTER_CONNECTION = "couponMasterRedisConnection";
    /** 잠정값. 회수 기준 시간(D-131) 계산에 들어간다 */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);

    @Qualifier(MASTER_CONNECTION)
    @Bean
    public LettuceConnectionFactory couponMasterRedisConnection(RedisProperties properties) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.MASTER)
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

    @Qualifier(MASTER_TEMPLATE)
    @Bean
    public StringRedisTemplate couponMasterRedisTemplate(@Qualifier(MASTER_CONNECTION) LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
