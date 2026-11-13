package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 쿠폰 수용 판단 전용 Redis 연결(docs/week8/decisions.md D-14, D-16). 공용 RedisConfig(modules/redis)는 그대로 두고 접속 정보만 쓴다.
 *
 * <ul>
 *   <li>master에만 붙는다(replica를 등록하지 않는다) — replica에서 읽은 수로 자리를 승인할 길이 구조적으로 없다.</li>
 *   <li>명령 timeout 500ms. 공용 연결은 Lettuce 기본 60초라, 대답 없는 Redis 앞에서 요청 스레드가 묶이고 fail-closed(D-2)도 늦어진다.</li>
 *   <li>연결 수립(TCP + handshake)은 1초 — Windows Docker에서 짧은 handshake timeout에 걸렸던 4주차 경험(CacheRedisConfig).</li>
 *   <li>끊기면 대기열에 쌓지 않고 즉시 거절한다.</li>
 * </ul>
 * 수용 판단과 반환은 이 템플릿만 쓴다. @Primary가 아니다 — 다른 Redis 용도에 이 설정이 번지지 않는다.
 */
@Configuration
public class AdmissionRedisConfig {

    public static final String ADMISSION_REDIS_TEMPLATE = "admissionRedisTemplate";
    public static final String ADMISSION_CONNECTION_FACTORY = "admissionRedisConnectionFactory";

    @Qualifier(ADMISSION_CONNECTION_FACTORY)
    @Bean(ADMISSION_CONNECTION_FACTORY)
    public LettuceConnectionFactory admissionRedisConnectionFactory(
        RedisProperties redisProperties,
        @Value("${coupon.admission.redis.command-timeout:500ms}") Duration commandTimeout,
        @Value("${coupon.admission.redis.connect-timeout:1s}") Duration connectTimeout
    ) {
        ClientOptions clientOptions = ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
            .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
            .build();
        // Lettuce는 RedisURI timeout(= 이 commandTimeout)을 handshake에도 쓴다. 명령 timeout은 위 TimeoutOptions가 정한다.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(connectTimeout)
            .clientOptions(clientOptions)
            .build();

        RedisNodeInfo master = redisProperties.master();
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(master.host(), master.port());
        serverConfig.setDatabase(redisProperties.database());
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Qualifier(ADMISSION_REDIS_TEMPLATE)
    @Bean(ADMISSION_REDIS_TEMPLATE)
    public RedisTemplate<String, String> admissionRedisTemplate(
        @Qualifier(ADMISSION_CONNECTION_FACTORY) LettuceConnectionFactory connectionFactory
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
