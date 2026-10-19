package com.loopers.config.cache;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 상품 목록 캐시 전용 Redis 연결. 읽기·쓰기 모두 master 로 보내고, 명령·연결 대기를 짧게 제한해
 * Redis 장애가 상품 조회 응답을 오래 붙잡지 않도록 한다.
 */
@Configuration
public class ProductCacheRedisConfig {

    public static final String PRODUCT_CACHE_REDIS_TEMPLATE = "productCacheRedisTemplate";
    private static final String PRODUCT_CACHE_CONNECTION_FACTORY = "productCacheRedisConnectionFactory";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(100);
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    @Qualifier(PRODUCT_CACHE_CONNECTION_FACTORY)
    @Bean
    public LettuceConnectionFactory productCacheRedisConnectionFactory(RedisProperties redisProperties) {
        RedisNodeInfo master = redisProperties.master();
        RedisStandaloneConfiguration masterConfig = new RedisStandaloneConfiguration(master.host(), master.port());
        masterConfig.setDatabase(redisProperties.database());

        ClientOptions clientOptions = ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
            .build();
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(COMMAND_TIMEOUT)
            .clientOptions(clientOptions)
            .build();

        return new LettuceConnectionFactory(masterConfig, clientConfig);
    }

    @Qualifier(PRODUCT_CACHE_REDIS_TEMPLATE)
    @Bean
    public RedisTemplate<String, String> productCacheRedisTemplate(
        @Qualifier(PRODUCT_CACHE_CONNECTION_FACTORY) LettuceConnectionFactory connectionFactory
    ) {
        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
