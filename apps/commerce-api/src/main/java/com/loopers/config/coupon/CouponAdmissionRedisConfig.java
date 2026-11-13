package com.loopers.config.coupon;

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
 * 쿠폰 수용량 통제 전용 Redis 연결 (8주차 설계 §4·§7-1, C6).
 * - 읽기·쓰기 모두 master 로 보낸다. replica 는 복제 지연이 있어 "자리가 비었다"고 읽은 순간 이미 찼을 수 있다 (§4)
 * - 명령 대기를 300ms 로 제한해 Redis 장애 시 fail-closed 판단이 빨리 끝난다. 기본 연결(60초)을 쓰면 요청이 60초간 걸린다 (§12-2)
 * - 연결이 끊긴 동안 명령을 큐에 쌓지 않고 즉시 거절한다(REJECT_COMMANDS). 쌓인 명령이 복구 뒤 한꺼번에 실행되면 수용 판단이 어긋난다
 */
@Configuration
public class CouponAdmissionRedisConfig {

    public static final String COUPON_ADMISSION_REDIS_TEMPLATE = "couponAdmissionRedisTemplate";
    public static final String COUPON_ADMISSION_CONNECTION_FACTORY = "couponAdmissionRedisConnectionFactory";
    static final Duration COMMAND_TIMEOUT = Duration.ofMillis(300);
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    @Qualifier(COUPON_ADMISSION_CONNECTION_FACTORY)
    @Bean
    public LettuceConnectionFactory couponAdmissionRedisConnectionFactory(RedisProperties redisProperties) {
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

    @Qualifier(COUPON_ADMISSION_REDIS_TEMPLATE)
    @Bean
    public RedisTemplate<String, String> couponAdmissionRedisTemplate(
        @Qualifier(COUPON_ADMISSION_CONNECTION_FACTORY) LettuceConnectionFactory connectionFactory
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
