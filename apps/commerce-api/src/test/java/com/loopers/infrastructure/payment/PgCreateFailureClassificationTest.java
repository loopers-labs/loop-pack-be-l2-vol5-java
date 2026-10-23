package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgCreateFailureClassificationTest {
    @ParameterizedTest
    @MethodSource("transportFailures")
    void onlyExactInitialPoolTimeoutCanMeanNotDispatched(IOException cause) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class).run(context -> {
            RestClient rest = mock(RestClient.class);
            RestClient.RequestBodyUriSpec request = mock(RestClient.RequestBodyUriSpec.class, RETURNS_SELF);
            when(rest.post()).thenReturn(request);
            when(request.exchange(any())).thenThrow(new ResourceAccessException("분류 대조군", cause));
            var registry = context.getBean(CircuitBreakerRegistry.class);
            var gateway = new PgHttpGateway(rest, context.getBean(PgResponseDecoder.class),
                context.getBean(PgHttpProperties.class), registry);
            assertThat(gateway.create(new PaymentRequest("LP-ORD-0000000011", 4000, "TEST", "0000-0000-0000-0000")))
                .isEqualTo(new PaymentObservation.Unavailable(ObservationSource.CREATE));
            assertThat(registry.circuitBreaker("pgCreate").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
            assertThat(registry.circuitBreaker("pgCreate").getMetrics().getNumberOfSuccessfulCalls()).isZero();
            verify(rest, times(1)).post();
            verify(request, times(1)).exchange(any());
        });
    }

    static Stream<IOException> transportFailures() {
        return Stream.of(new SocketTimeoutException("read/connect timeout"), new InterruptedIOException("interrupt"),
            new IOException("pool처럼 보이지만 lease timeout이 아님"),
            new IOException("cancelled transport", new CancellationException()),
            new ConnectionRequestTimeoutException("정확한 타입이 아닌 하위 예외") { });
    }
}
