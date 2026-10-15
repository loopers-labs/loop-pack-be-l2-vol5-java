package com.loopers.testcontainers;

import com.redis.testcontainers.RedisContainer;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class RedisTestContainersConfig {
    private static final RedisContainer redisContainer = new RedisContainer(DockerImageName.parse("redis:latest"));

    // 연결 주소는 RedisConfig가 datasource.redis.*를 바인딩하기 전에 들어가야 한다.
    // 생성자에서 넣으면 빈 생성 순서에 따라 redis.yml 기본값(localhost)으로 먼저 바인딩될 수 있어, 클래스 초기화 시점에 넣는다.
    static {
        redisContainer.start();
        System.setProperty("datasource.redis.database", "0");
        System.setProperty("datasource.redis.master.host", redisContainer.getHost());
        System.setProperty("datasource.redis.master.port", String.valueOf(redisContainer.getFirstMappedPort()));
        System.setProperty("datasource.redis.replicas[0].host", redisContainer.getHost());
        System.setProperty("datasource.redis.replicas[0].port", String.valueOf(redisContainer.getFirstMappedPort()));
    }

    // static @Bean BeanFactoryPostProcessor는 일반 빈보다 먼저 호출되므로, 이 호출로 위 static 블록 실행을 앞당긴다.
    @Bean
    public static BeanFactoryPostProcessor redisTestContainersInitializer() {
        return beanFactory -> { };
    }
}
