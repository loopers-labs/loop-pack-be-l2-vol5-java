package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * connectTimeout·commandTimeout 은 선택 값이다. 지정하지 않으면 Lettuce 기본값을 사용한다.
 */
@ConfigurationProperties(value = "datasource.redis")
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        Duration connectTimeout,
        Duration commandTimeout
) { }
