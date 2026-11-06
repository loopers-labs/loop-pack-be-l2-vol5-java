package com.loopers.config.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * commandTimeout·connectTimeout·rejectCommandsWhenDisconnected는 선택 값이다.
 * 지정하지 않으면 Lettuce 기본 동작(명령 대기 60초, 연결이 끊긴 동안 명령을 쌓아 두고 재연결을 기다림)을 그대로 쓴다.
 */
@ConfigurationProperties(value = "datasource.redis")
public record RedisProperties(
        int database,
        RedisNodeInfo master,
        List<RedisNodeInfo> replicas,
        Duration commandTimeout,
        Duration connectTimeout,
        boolean rejectCommandsWhenDisconnected
) { }
