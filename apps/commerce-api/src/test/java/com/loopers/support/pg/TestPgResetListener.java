package com.loopers.support.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 모든 Spring 테스트 메서드 전에 테스트 PG를 기본 응답(전부 202 PENDING)으로 되돌리고 받은 요청 기록을 비우며,
 * PG 조회 circuit breaker를 닫힌 상태로 되돌린다.
 * 앞 테스트가 더한 stub이나 요청 수가 다음 테스트에 남지 않게 한다. 순서는 RedisCacheCleanUpListener 옆이다.
 * 등록: src/test/resources/META-INF/spring.factories
 */
public class TestPgResetListener extends AbstractTestExecutionListener {

    private static final int ORDER = 5_600;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        TestPg.reset();
        // 실패를 일부러 일으키는 테스트가 쌓여 회로가 열린 채 다음 테스트로 넘어가지 않게 한다.
        testContext.getApplicationContext()
            .getBeanProvider(CircuitBreaker.class)
            .ifAvailable(CircuitBreaker::reset);
    }
}
