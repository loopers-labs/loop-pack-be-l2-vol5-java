package com.loopers.support.pg;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 모든 Spring 테스트를 공유 테스트 PG 에 연결함 (META-INF/spring.factories 로 등록).
 * 컨텍스트 생성 전에 넣어 @ConfigurationProperties 바인딩보다 항상 앞섬. timeout 장면이 오래 걸리지 않게 대기를 짧게 둠
 */
public class TestPgContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testPg", Map.of(
            "payment.test-pg.base-url", TestPgServer.shared().baseUrl(),
            "payment.test-pg.create-read-timeout", "1s",
            "payment.test-pg.lookup-read-timeout", "500ms"
        )));
    }
}
