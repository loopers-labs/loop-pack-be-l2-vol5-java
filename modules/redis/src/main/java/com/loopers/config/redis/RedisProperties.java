package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(value = "datasource.redis")
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        Duration commandTimeout
) { }
