package com.loopers.config.coupon;

import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class CouponCounterRedisConfig {
    @Bean("couponCounterRedisConnectionFactory")
    public LettuceConnectionFactory connectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
            properties.master().host(), properties.master().port());
        server.setDatabase(properties.database());
        ClientOptions options = ClientOptions.builder().socketOptions(SocketOptions.builder()
            .connectTimeout(Duration.ofSeconds(1)).build()).build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(1)).readFrom(ReadFrom.MASTER).clientOptions(options).build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean("couponCounterRedisTemplate")
    public RedisTemplate<String, String> template(
            @Qualifier("couponCounterRedisConnectionFactory") LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setConnectionFactory(factory);
        return template;
    }
}
