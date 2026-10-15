package com.loopers.testcontainers;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 테스트가 테스트 컨테이너 Redis에 붙도록, 컨텍스트가 만들어지기 전에 컨테이너 주소를 넣는다.
 *
 * <p>공용 픽스처 {@link RedisTestContainersConfig}는 주소를 <b>생성자</b>에서 시스템 프로퍼티로 넣는다. 그런데 공용
 * RedisConfig가 그보다 먼저 만들어지며 RedisProperties가 redis.yml 기본값(localhost:6379/6380, 개발용 로컬 Redis)으로
 * 확정되어, 테스트가 로컬 Redis에 붙었다(MySQL 픽스처는 static 블록에서 넣어 문제가 없다). 공용 모듈은 수정하지 않는
 * 규칙이라 원인은 그대로 두고, 여기서 픽스처를 미리 한 번 생성해 생성자가 먼저 실행되게 한다.
 * 등록: src/test/resources/META-INF/spring.factories
 */
public class RedisTestContainersInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        new RedisTestContainersConfig();
    }
}
