package com.loopers.support.pg;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 컨텍스트가 만들어지기 전에 PG 주소를 테스트 PG로 바꾼다. PG adapter는 생성자에서 주소를 읽으므로 그 전이어야 한다.
 * timeout은 테스트 시간을 줄이려고 운영값보다 짧게 둔다 — 운영 수치가 아니라 "timeout이면 UNKNOWN" 같은 동작을 본다.
 * 등록: src/test/resources/META-INF/spring.factories
 */
public class TestPgInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertyValues.of(
            "pg.base-url=" + TestPg.baseUrl(),
            "pg.create.read-timeout=500ms",
            "pg.lookup.read-timeout=300ms",
            "pg.lookup.retry-initial-wait=10ms",
            // 백그라운드 회복 작업이 테스트 데이터를 같이 처리하지 않게 끈다 — 테스트는 PaymentRecoveryJob을 직접 부른다.
            "payment.recovery.enabled=false"
        ).applyTo(applicationContext);
    }
}
