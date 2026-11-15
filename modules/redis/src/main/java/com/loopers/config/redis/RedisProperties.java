package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(value = "datasource.redis")
/**
 * @param commandTimeout 선택값. 없으면 Lettuce 기본값을 쓴다. 앱마다 필요한 값을 지정한다.
 */
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        Duration commandTimeout
) { }
