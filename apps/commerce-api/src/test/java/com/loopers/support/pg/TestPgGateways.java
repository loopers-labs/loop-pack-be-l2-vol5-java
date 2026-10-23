package com.loopers.support.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.infrastructure.payment.client.TestPgLookupPolicyProperties;
import com.loopers.infrastructure.payment.client.TestPgPaymentGateway;
import com.loopers.infrastructure.payment.client.TestPgProperties;
import com.loopers.infrastructure.payment.client.TestPgResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

import java.time.Duration;

/** Spring 없이 adapter 를 만드는 도우미. 회로는 adapter 마다 새로 만듦 */
public final class TestPgGateways {

    public static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";

    private TestPgGateways() {
    }

    /** 운영 기본값과 같은 재조회 · 회로 */
    public static TestPgLookupPolicyProperties defaultPolicy() {
        return new TestPgLookupPolicyProperties(3, Duration.ofMillis(100), 2, 10, 5, 50, Duration.ofSeconds(10), 2);
    }

    /** 재조회 없이 한 번만 보내고 회로가 열리지 않음. 응답 분류만 볼 때 씀 */
    public static TestPgLookupPolicyProperties singleAttemptPolicy() {
        return new TestPgLookupPolicyProperties(1, Duration.ofMillis(100), 2, 100, 100, 100, Duration.ofSeconds(10), 2);
    }

    public static TestPgPaymentGateway gateway(String baseUrl, Duration readTimeout, TestPgLookupPolicyProperties policy) {
        return gateway(baseUrl, readTimeout, TestPgResilienceConfig.lookupCircuitBreaker(policy), TestPgResilienceConfig.lookupRetry(policy));
    }

    /** 회로 상태를 테스트에서 볼 때 씀 */
    public static TestPgPaymentGateway gateway(String baseUrl, Duration readTimeout, CircuitBreaker circuitBreaker, Retry retry) {
        TestPgProperties properties = new TestPgProperties(baseUrl, CALLBACK_URL, "135135", Duration.ofSeconds(1), readTimeout, readTimeout);
        return new TestPgPaymentGateway(properties, new ObjectMapper(), circuitBreaker, retry);
    }
}
