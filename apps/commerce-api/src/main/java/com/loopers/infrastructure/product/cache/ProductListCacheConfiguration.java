package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.product.ProductListCache;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(ProductListCacheProperties.class)
public class ProductListCacheConfiguration {
    public static final String CONNECTION = "productListCacheConnectionFactory";
    public static final String TEMPLATE = "productListCacheRedisTemplate";

    @Bean(CONNECTION)
    public LettuceConnectionFactory connectionFactory(RedisProperties redis, ProductListCacheProperties cache) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(redis.master().host(), redis.master().port());
        server.setDatabase(redis.database());
        ClientOptions options = ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(cache.connectTimeout()).build())
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .commandTimeout(cache.commandTimeout()).clientOptions(options).build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean(TEMPLATE)
    public StringRedisTemplate redisTemplate(@Qualifier(CONNECTION) LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public ProductListCache productListCache(@Qualifier(TEMPLATE) StringRedisTemplate redis,
                                            ObjectMapper mapper, ProductListCacheProperties properties,
                                            MeterRegistry registry) {
        return new RedisProductListCache(redis, mapper, properties, registry);
    }
}
