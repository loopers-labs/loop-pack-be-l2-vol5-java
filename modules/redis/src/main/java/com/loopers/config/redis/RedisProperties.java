package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.util.Collections;

@ConfigurationProperties(value = "datasource.redis")
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        @DefaultValue("100ms") Duration commandTimeout,
        @DefaultValue("100ms") Duration connectTimeout,
        @DefaultValue("1s") Duration reconnectMaxDelay
) {
    public RedisProperties {
        replicas = replicas == null ? Collections.emptyList() : List.copyOf(replicas);
    }
}
