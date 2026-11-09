package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 payment.pg 설정이 PgProperties로 바인딩되고 adapter가 PaymentGateway 빈으로 올라오는지 확인한다.
 * DB·Redis 자동 설정 없이 PG 설정 클래스만 올린다.
 */
@SpringBootTest(classes = {PgClientConfig.class, TestPgPaymentGateway.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PgClientConfigTest {

    @Autowired
    private PgProperties properties;

    @Autowired
    private PaymentGateway paymentGateway;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DisplayName("payment.pg 설정이 주소·제공자 계정·시간 상한으로 바인딩된다.")
    @Test
    void bindsPaymentPgProperties() {
        // assert
        // 주소는 다른 테스트가 같은 JVM에서 공유 테스트 PG의 임의 포트로 덮어쓸 수 있어, 고정한 로컬 주소(127.0.0.1)인지만 확인한다
        assertThat(properties.baseUrl()).startsWith("http://127.0.0.1:");
        assertThat(properties.callbackUrl()).isEqualTo("http://127.0.0.1:8080/api/v1/payments/callback");
        assertThat(properties.providerUserId()).isEqualTo("135135");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.createReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.lookupReadTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.lookupRetry()).isEqualTo(new PgProperties.LookupRetry(3, Duration.ofMillis(100), 2.0));
        assertThat(properties.lookupCircuitBreaker())
            .isEqualTo(new PgProperties.LookupCircuitBreaker(10, 5, 50f, Duration.ofSeconds(10), 2));
    }

    @DisplayName("조회 회로 차단기는 설정값으로 만들어져 registry에 pgLookup 이름으로 등록된다.")
    @Test
    void registersLookupCircuitBreakerWithConfiguredValues() {
        // act
        var config = circuitBreakerRegistry.circuitBreaker(TestPgPaymentGateway.LOOKUP_CIRCUIT_BREAKER).getCircuitBreakerConfig();

        // assert
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
    }

    @DisplayName("PaymentGateway 빈은 테스트 PG adapter다.")
    @Test
    void registersTestPgAdapterAsPaymentGateway() {
        // assert
        assertThat(paymentGateway).isInstanceOf(TestPgPaymentGateway.class);
    }
}
