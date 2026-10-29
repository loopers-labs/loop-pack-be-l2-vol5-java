package com.loopers.testcontainers;

import com.redis.testcontainers.RedisContainer;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class RedisTestContainersConfig {
    private static final RedisContainer redisContainer = new RedisContainer(DockerImageName.parse("redis:latest"));

    // 생성자에서 넣으면 RedisConfig 가 먼저 만들어질 때 기본 포트로 묶이므로 클래스 초기화 때 넣음 (MySqlTestContainersConfig 와 같음)
    static {
        // 로컬 compose 로 앱 사이를 잇는 실험에서만 켬. test profile 의 localhost 주소를 그대로 씀 (6주차 설계 7.2)
        if (!"true".equals(System.getenv("TEST_LOCAL_INFRA"))) {
            redisContainer.start();

            System.setProperty("datasource.redis.database", "0");
            System.setProperty("datasource.redis.master.host", redisContainer.getHost());
            System.setProperty("datasource.redis.master.port", String.valueOf(redisContainer.getFirstMappedPort()));
            System.setProperty("datasource.redis.replicas[0].host", redisContainer.getHost());
            System.setProperty("datasource.redis.replicas[0].port", String.valueOf(redisContainer.getFirstMappedPort()));
        }
    }
}
