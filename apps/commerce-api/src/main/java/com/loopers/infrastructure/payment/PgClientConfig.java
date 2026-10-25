package com.loopers.infrastructure.payment;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.time.Duration;

@Configuration
class PgClientConfig {

    @Bean(destroyMethod = "")
    PoolingHttpClientConnectionManager pgCreateConnectionPool(PgProperties pg, SslBundles sslBundles) {
        return pool(pg, sslBundles, pg.createConcurrency());
    }

    @Bean(destroyMethod = "")
    PoolingHttpClientConnectionManager pgLookupConnectionPool(PgProperties pg, SslBundles sslBundles) {
        return pool(pg, sslBundles, pg.lookupMaxConnections());
    }

    @Bean(destroyMethod = "close")
    CloseableHttpClient pgCreateHttpClient(@Qualifier("pgCreateConnectionPool") PoolingHttpClientConnectionManager pool) {
        return httpClient(pool);
    }

    @Bean(destroyMethod = "close")
    CloseableHttpClient pgLookupHttpClient(@Qualifier("pgLookupConnectionPool") PoolingHttpClientConnectionManager pool) {
        return httpClient(pool);
    }

    @Bean
    MeterBinder pgCreateConnectionPoolMetrics(@Qualifier("pgCreateConnectionPool") PoolingHttpClientConnectionManager pool) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(pool, "pg-create");
    }

    @Bean
    MeterBinder pgLookupConnectionPoolMetrics(@Qualifier("pgLookupConnectionPool") PoolingHttpClientConnectionManager pool) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(pool, "pg-lookup");
    }

    @Bean
    BulkheadRegistry pgBulkheads(PgProperties pg) {
        return BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(pg.createConcurrency())
            .maxWaitDuration(Duration.ZERO)
            .build());
    }

    @Bean
    Bulkhead pgCreateBulkhead(BulkheadRegistry pgBulkheads) {
        return pgBulkheads.bulkhead("pg-create");
    }

    @Bean
    MeterBinder pgBulkheadMetrics(BulkheadRegistry pgBulkheads) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(pgBulkheads);
    }

    @Bean
    CircuitBreakerRegistry pgCircuitBreakers(PgProperties pg) {
        PgProperties.LookupCircuit circuit = pg.lookupCircuit();
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize((int) circuit.window().toSeconds())
            .minimumNumberOfCalls(circuit.minimumCalls())
            .failureRateThreshold(circuit.failureRatePercent())
            .slowCallDurationThreshold(circuit.slowCallDuration())
            .slowCallRateThreshold(circuit.slowCallRatePercent())
            .waitDurationInOpenState(circuit.openWait())
            .permittedNumberOfCallsInHalfOpenState(circuit.halfOpenCalls())
            .recordResult(result -> result instanceof PgPaymentGateway.LookupAttempt attempt && attempt.failed())
            .ignoreExceptions(PgPaymentGateway.LookupPoolExhausted.class)
            .build());
    }

    @Bean
    CircuitBreaker pgLookupCircuitBreaker(CircuitBreakerRegistry pgCircuitBreakers) {
        return pgCircuitBreakers.circuitBreaker("pg-lookup");
    }

    @Bean
    MeterBinder pgCircuitBreakerMetrics(CircuitBreakerRegistry pgCircuitBreakers) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(pgCircuitBreakers);
    }

    @Bean
    RestClient pgCreateClient(
        RestClient.Builder builder, @Qualifier("pgCreateHttpClient") CloseableHttpClient http, PgProperties pg
    ) {
        return client(builder, http, pg, pg.createTimeout());
    }

    @Bean
    RestClient pgLookupClient(
        RestClient.Builder builder, @Qualifier("pgLookupHttpClient") CloseableHttpClient http, PgProperties pg
    ) {
        return client(builder, http, pg, pg.lookupTimeout());
    }

    private static PoolingHttpClientConnectionManager pool(PgProperties pg, SslBundles sslBundles, int size) {
        return PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(size)
            .setMaxConnPerRoute(size)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(pg.connectTimeout().toMillis()))
                .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                .build())
            .setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext(pg, sslBundles)))
            .build();
    }

    private static CloseableHttpClient httpClient(PoolingHttpClientConnectionManager pool) {
        return HttpClients.custom()
            .setConnectionManager(pool)
            .disableAutomaticRetries()
            .evictIdleConnections(TimeValue.ofSeconds(30))
            .build();
    }

    private static SSLContext sslContext(PgProperties pg, SslBundles sslBundles) {
        if (pg.sslBundle() == null) {
            return SSLContexts.createDefault();
        }
        return sslBundles.getBundle(pg.sslBundle()).createSslContext();
    }

    private static RestClient client(RestClient.Builder builder, CloseableHttpClient http, PgProperties pg, Duration readTimeout) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(http);
        factory.setConnectionRequestTimeout(pg.poolWaitTimeout());
        factory.setReadTimeout(readTimeout);
        return builder.clone()
            .requestFactory(factory)
            .baseUrl(pg.baseUrl())
            .defaultHeader("X-USER-ID", pg.userId())
            .build();
    }
}
