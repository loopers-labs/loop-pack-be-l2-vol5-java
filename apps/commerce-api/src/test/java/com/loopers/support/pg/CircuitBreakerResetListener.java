package com.loopers.support.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * 테스트끼리 공유하는 컨텍스트의 회로 상태가 다음 테스트로 새지 않도록 매 테스트 전에 CLOSED 로 되돌림.
 * META-INF/spring.factories 로 등록함
 */
public class CircuitBreakerResetListener implements TestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (testContext.hasApplicationContext()) {
            testContext.getApplicationContext().getBeanProvider(CircuitBreaker.class).forEach(CircuitBreaker::reset);
        }
    }
}
