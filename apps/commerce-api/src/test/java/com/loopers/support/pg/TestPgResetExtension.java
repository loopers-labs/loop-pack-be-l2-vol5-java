package com.loopers.support.pg;

import com.loopers.infrastructure.payment.TestPgPaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * PG를 쓰는 Spring 테스트가 서로 영향을 주지 않게, 테스트마다 앞뒤로 공유 PG의 거래·호출 수·통제와 조회 회로 차단기를 초기화한다.
 * 이전 테스트가 남긴 거래가 주문별 조회에 섞이면 같은 주문 번호의 거래가 2건처럼 보여 충돌로 기록되고,
 * 앞 테스트의 실패 표본이 회로를 열어 두면 뒤 테스트의 조회가 PG에 닿지 않는다.
 */
public class TestPgResetExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        reset(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        reset(context);
    }

    private static void reset(ExtensionContext context) {
        TestPgServer.shared().reset();
        ApplicationContext applicationContext = SpringExtension.getApplicationContext(context);
        applicationContext.getBean(CircuitBreakerRegistry.class)
            .circuitBreaker(TestPgPaymentGateway.LOOKUP_CIRCUIT_BREAKER)
            .reset();
    }
}
