package com.loopers.domains.coupon.config;

import com.loopers.config.redis.RedisProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// 수용 전용 Redis 연결(D137). 공용 master template과 같은 호스트를 쓰되 명령 timeout만 따로 둔다(D135 500ms).
// 공용 설정(datasource.redis.command-timeout)은 상품 목록 캐시도 쓰므로 바꾸지 않는다.
@Configuration
public class CouponAdmissionRedisConfig {
    private static final String CONNECTION = "couponAdmissionRedisConnectionFactory";

    @Bean(name = CONNECTION)
    LettuceConnectionFactory couponAdmissionRedisConnectionFactory(RedisProperties redisProperties,
        @Value("${commerce.coupon.admission.redis-command-timeout}") Duration commandTimeout) {
        RedisStandaloneConfiguration master = new RedisStandaloneConfiguration(redisProperties.master().host(),
            redisProperties.master().port());
        master.setDatabase(redisProperties.database());
        return new LettuceConnectionFactory(master, LettuceClientConfiguration.builder().commandTimeout(commandTimeout).build());
    }

    @Bean(name = "couponAdmissionRedisTemplate")
    RedisTemplate<String, String> couponAdmissionRedisTemplate(
        @Qualifier(CONNECTION) LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<String, String>();
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
