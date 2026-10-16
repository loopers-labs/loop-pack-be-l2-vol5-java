package com.loopers.infrastructure.product;

import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class ProductCacheRedisConfig {
    @Bean("productCacheRedisConnection")
    public LettuceConnectionFactory connection(RedisProperties properties) {
        var server = new RedisStandaloneConfiguration(properties.master().host(), properties.master().port());
        server.setDatabase(properties.database());
        var socket = SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build();
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200))
            .shutdownTimeout(Duration.ofMillis(200))
            .clientOptions(ClientOptions.builder().socketOptions(socket).build()).build();
        return new LettuceConnectionFactory(server, client);
    }
    @Bean("productCacheRedisTemplate")
    public StringRedisTemplate template(@Qualifier("productCacheRedisConnection") LettuceConnectionFactory connection) {
        return new StringRedisTemplate(connection);
    }
}
