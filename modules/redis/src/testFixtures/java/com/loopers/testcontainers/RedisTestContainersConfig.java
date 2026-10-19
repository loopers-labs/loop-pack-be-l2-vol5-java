package com.loopers.testcontainers;

import com.redis.testcontainers.RedisContainer;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class RedisTestContainersConfig {
    private static final RedisContainer redisContainer = new RedisContainer(DockerImageName.parse("redis:latest"));

    // RedisProperties 는 RedisConfig 생성 시점에 바인딩되므로, 생성자가 아니라 클래스 초기화 때 주소를 넣어야
    // 컨테이너 주소가 반영된다 (MySqlTestContainersConfig 와 같은 방식).
    static {
        redisContainer.start();

        System.setProperty("datasource.redis.database", "0");
        System.setProperty("datasource.redis.master.host", redisContainer.getHost());
        System.setProperty("datasource.redis.master.port", String.valueOf(redisContainer.getFirstMappedPort()));
        System.setProperty("datasource.redis.replicas[0].host", redisContainer.getHost());
        System.setProperty("datasource.redis.replicas[0].port", String.valueOf(redisContainer.getFirstMappedPort()));
    }
}
