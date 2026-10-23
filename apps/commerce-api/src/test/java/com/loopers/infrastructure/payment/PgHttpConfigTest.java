package com.loopers.infrastructure.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.Configurable;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 application.yml 을 읽어 PG 설정 바인딩·회로 registry·HTTP client bean 조립을 확인한다.
 * 회로 판정(predicate)과 실제 HTTP 보호는 이 테스트의 범위가 아니다(I2-3·I2-4).
 */
@DisplayName("PG HTTP·회로 설정은 application.yml 값으로 조립된다.")
class PgHttpConfigTest {

    static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class))
        .withUserConfiguration(PgHttpConfig.class, PgCircuitConfig.class, PropertiesConfiguration.class);

    @DisplayName("commerce.pg 를 불변 설정으로 바인딩한다.")
    @Test
    void bindsPgProperties() {
        RUNNER.run(context -> assertThat(context.getBean(PgHttpProperties.class)).isEqualTo(new PgHttpProperties(
            URI.create("http://127.0.0.1:18080"),
            URI.create("http://127.0.0.1:8080/api/v1/payments/callback"),
            "135135",
            10,
            Duration.ofMillis(200),
            Duration.ofMillis(300),
            Duration.ofSeconds(1))));
    }

    @DisplayName("registry 에 pgCreate·pgLookup 만 있고 둘 다 pgBase 승인 수치를 라이브러리 기본값 대신 쓴다.")
    @Test
    void appliesApprovedCircuitValues() {
        RUNNER.run(context -> {
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            assertThat(registry.getAllCircuitBreakers()).extracting(CircuitBreaker::getName)
                .containsExactlyInAnyOrder("pgCreate", "pgLookup");
            assertAll(
                () -> assertApprovedValues(registry.circuitBreaker("pgCreate").getCircuitBreakerConfig()),
                () -> assertApprovedValues(registry.circuitBreaker("pgLookup").getCircuitBreakerConfig())
            );
        });
    }

    @DisplayName("pgCreate·pgLookup 에 각자의 결과 타입으로 직접 위반 실패·풀 대기/프로그램 오류 제외 판정을 따로 연결한다.")
    @Test
    void appliesSeparateClassification() {
        RUNNER.run(context -> {
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            CircuitBreakerConfig lookup = registry.circuitBreaker("pgLookup").getCircuitBreakerConfig();
            CircuitBreakerConfig create = registry.circuitBreaker("pgCreate").getCircuitBreakerConfig();
            PgLookupResult violation = new PgLookupResult(null, true);
            PgCreateResult createViolation = new PgCreateResult(null, true);
            PgPoolAcquireException poolWait = new PgPoolAcquireException(new RuntimeException());

            assertAll(
                () -> assertThat(lookup.getRecordResultPredicate().test(violation)).isTrue(),
                () -> assertThat(lookup.getRecordResultPredicate().test(new PgLookupResult(null, false))).isFalse(),
                () -> assertThat(lookup.getIgnoreExceptionPredicate().test(poolWait)).isTrue(),
                () -> assertThat(lookup.getIgnoreExceptionPredicate()
                    .test(new PgAdapterProgrammingException("x", new RuntimeException()))).isTrue(),
                () -> assertThat(lookup.getIgnoreExceptionPredicate().test(new PgLookupUnavailableException("x"))).isFalse(),
                () -> assertThat(lookup.getRecordResultPredicate().test(createViolation)).isFalse(),
                () -> assertThat(create.getRecordResultPredicate().test(createViolation)).isTrue(),
                () -> assertThat(create.getRecordResultPredicate().test(new PgCreateResult(null, false))).isFalse(),
                () -> assertThat(create.getRecordResultPredicate().test(violation)).as("조회 결과 타입은 생성 판정 대상 아님").isFalse(),
                () -> assertThat(create.getIgnoreExceptionPredicate().test(poolWait)).isTrue(),
                () -> assertThat(create.getIgnoreExceptionPredicate()
                    .test(new PgAdapterProgrammingException("x", new RuntimeException()))).isTrue(),
                () -> assertThat(create.getIgnoreExceptionPredicate().test(new PgCreateUnavailableException("x"))).isFalse(),
                () -> assertApprovedValues(lookup),
                () -> assertApprovedValues(create)
            );
        });
    }

    @DisplayName("두 회로는 같은 registry 인스턴스를 재사용하며 상태가 서로 독립이다.")
    @Test
    void keepsCircuitsIndependent() {
        RUNNER.run(context -> {
            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            CircuitBreaker create = registry.circuitBreaker("pgCreate");
            CircuitBreaker lookup = registry.circuitBreaker("pgLookup");

            create.transitionToOpenState();

            assertAll(
                () -> assertThat(registry.circuitBreaker("pgCreate")).isSameAs(create),
                () -> assertThat(lookup).isNotSameAs(create),
                () -> assertThat(create.getState()).isEqualTo(CircuitBreaker.State.OPEN),
                () -> assertThat(lookup.getState()).isEqualTo(CircuitBreaker.State.CLOSED)
            );
        });
    }

    @DisplayName("PG RestClient 에는 재시도 등 요청 interceptor 가 없다(POOL_ACQUIRE_TIMEOUT 보장 조건).")
    @Test
    void hasNoRequestInterceptor() {
        RUNNER.run(context -> {
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
            context.getBean("pgRestClient", RestClient.class).mutate()
                .requestInterceptors(interceptors::addAll);

            assertThat(interceptors).isEmpty();
        });
    }

    @Test
    void appliesTimeoutsPoolAndNoRetryOrRedirectToActualClient() {
        RUNNER.run(context -> {
            var client = context.getBean("pgHttpClient", CloseableHttpClient.class);
            var request = ((Configurable) client).getConfig();
            var pool = (PoolingHttpClientConnectionManager) ReflectionTestUtils.getField(client, "connManager");
            assertThat(pool).isNotNull();
            ConnectionConfig connection = ReflectionTestUtils.invokeMethod(pool, "resolveConnectionConfig",
                new HttpRoute(new HttpHost("http", "127.0.0.1", 18080)));
            assertThat(connection).isNotNull();
            assertThat(connection.getConnectTimeout().toMilliseconds()).isEqualTo(300);
            assertThat(connection.getSocketTimeout().toMilliseconds()).isEqualTo(1000);
            assertThat(request.getConnectionRequestTimeout().toMilliseconds()).isEqualTo(200);
            assertThat(request.getResponseTimeout().toMilliseconds()).isEqualTo(1000);
            assertThat(request.isRedirectsEnabled()).isFalse();
            assertThat(pool.getMaxTotal()).isEqualTo(10);
            assertThat(pool.getDefaultMaxPerRoute()).isEqualTo(10);

            Object chain = ReflectionTestUtils.getField(client, "execChain");
            List<String> handlers = new ArrayList<>();
            while (chain != null) {
                Object handler = ReflectionTestUtils.getField(chain, "handler");
                assertThat(handler).isNotNull();
                handlers.add(handler.getClass().getSimpleName());
                chain = ReflectionTestUtils.getField(chain, "next");
            }
            assertThat(handlers).contains("MainClientExec")
                .noneMatch(name -> name.contains("Retry") || name.contains("Redirect"));
            assertThat(CircuitBreaker.class.getProtectionDomain().getCodeSource().getLocation().toString())
                .contains("resilience4j-circuitbreaker-2.2.0.jar");
        });
    }

    @DisplayName("PG 전용 client·RestClient bean 을 만들고, 컨텍스트 종료 때 client 와 연결 풀을 닫는다.")
    @Test
    void closesClientWithContext() {
        AtomicReference<CloseableHttpClient> client = new AtomicReference<>();

        RUNNER.run(context -> {
            assertThat(context).hasSingleBean(RestClient.class).hasBean("pgRestClient");
            client.set(context.getBean("pgHttpClient", CloseableHttpClient.class));
        });

        assertThatThrownBy(() -> client.get().execute(new HttpGet("http://127.0.0.1:1/"), response -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shut down");
    }

    @DisplayName("잘못된 commerce.pg 값이면 컨텍스트 기동이 실패한다.")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "commerce.pg.max-connections=0",
        "commerce.pg.pool-acquire-timeout=0ms",
        "commerce.pg.connect-timeout=-1ms",
        "commerce.pg.read-timeout=0s",
        "commerce.pg.account-id= ",
        "commerce.pg.base-url=ftp://127.0.0.1:18080",
        "commerce.pg.base-url=pg-host",
        "commerce.pg.callback-url="
    })
    void rejectsInvalidProperties(String invalid) {
        RUNNER.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("commerce.pg."));
    }

    private static void assertApprovedValues(CircuitBreakerConfig config) {
        assertAll(
            () -> assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED),
            () -> assertThat(config.getSlidingWindowSize()).isEqualTo(10),
            () -> assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5),
            () -> assertThat(config.getFailureRateThreshold()).isEqualTo(50f),
            () -> assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(800)),
            () -> assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f),
            () -> assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(5_000L),
            () -> assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2),
            () -> assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse(),
            () -> assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(10))
        );
    }

    @EnableConfigurationProperties(PgHttpProperties.class)
    static class PropertiesConfiguration {
    }
}
