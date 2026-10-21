package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(value = "datasource.redis")
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        // 명령 응답 대기 상한. 지정하지 않으면 Lettuce 기본값(60초)을 쓴다.
        Duration commandTimeout
) { }
