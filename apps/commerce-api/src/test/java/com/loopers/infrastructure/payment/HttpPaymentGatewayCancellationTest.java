package com.loopers.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loopers.application.payment.PaymentGateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CancellationException;

class HttpPaymentGatewayCancellationTest {
    private RestClient httpClient;
    private HttpPaymentGateway paymentGateway;

    @BeforeEach
    void setUp() {
        httpClient = mock(RestClient.class);
        var builder = mock(RestClient.Builder.class, RETURNS_SELF);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(httpClient);
        paymentGateway = new HttpPaymentGateway(builder, URI.create("http://127.0.0.1:8080"),
                URI.create("http://127.0.0.1:8080/api/v1/payments/callback"),
                Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        paymentGateway.close();
    }

    @Test
    void HTTP_클라이언트가_결제_생성_요청을_취소하면_결과_미확인_예외로_전달한다() {
        // arrange
        var requestCancellation = new CancellationException();
        when(httpClient.post()).thenThrow(requestCancellation);

        // act
        Throwable creationFailure = catchThrowable(() ->
                paymentGateway.create("LP-ORD-0000000042", 7_000, "TEST", "0000-0000-0000-0000"));

        // assert
        assertThat(creationFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class)
                .hasCause(requestCancellation);
    }

    @Test
    void HTTP_클라이언트가_PG_조회_요청을_취소하면_서킷의_통신_실패로_집계한다() {
        // arrange
        when(httpClient.get()).thenThrow(new CancellationException());
        var lookupCircuit = new PaymentLookupCircuitConfig().paymentLookupCircuitBreaker(
                10, 10, 50, Duration.ofSeconds(10), 1);
        var protectedGateway = new CircuitBreakingPaymentGateway(paymentGateway, lookupCircuit);

        // act
        Throwable lookupFailure = catchThrowable(() -> protectedGateway.findByOrderId("LP-ORD-0000000042"));

        // assert
        assertThat(lookupFailure).isInstanceOf(PaymentGateway.UnconfirmedException.class);
        assertThat(lookupCircuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }
}
