package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * timeout 은 명령 하나의 응답 대기 상한이다. 비워 두면 Lettuce 기본값(60초)을 따른다.
 * 캐시처럼 Redis 장애 때 DB 로 넘어가야 하는 호출이 오래 묶이지 않도록 짧게 둔다.
 */
@ConfigurationProperties(value = "datasource.redis")
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        Duration timeout
) { }
