package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentLookupObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// JDK 응답 Future의 직접 취소를 실제 Spring request factory와 RestClient 경로로 재현한다.
class TestPgPaymentGatewayCancellationTest {
    @DisplayName("HTTP 응답 Future가 취소되면 결과 미확인으로 변환하고 요청을 다시 보내지 않는다.")
    @ParameterizedTest(name = "HTTP 응답 Future 취소: {0}")
    @ValueSource(strings = {"CREATE", "TRANSACTION", "ORDER"})
    void mapsCancelledHttpResponseWithoutResending(String operation) {
        HttpClient client = mock(HttpClient.class);
        HttpClient.Builder builder = mock(HttpClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(client);
        CompletableFuture<HttpResponse<InputStream>> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        when(client.<InputStream>sendAsync(any(HttpRequest.class), any())).thenReturn(cancelled);

        try (var httpClients = mockStatic(HttpClient.class)) {
            httpClients.when(HttpClient::newBuilder).thenReturn(builder);
            TestPgPaymentGateway gateway = new TestPgPaymentGateway(new PgProperties(
                "http://127.0.0.1:8082", "http://127.0.0.1:8080/api/v1/payments/callback", "135135",
                Duration.ofSeconds(1), Duration.ofMillis(500)), new ObjectMapper());
            switch (operation) {
                case "CREATE" -> assertThat(gateway.create(new PaymentCreateCommand(
                    "LP-ORD-0000000042", 7000, "TEST", "0000-0000-0000-0000")))
                    .isEqualTo(new PaymentCreateObservation.Unknown("CancellationException"));
                case "TRANSACTION" -> assertThat(gateway.lookup("TEST-TX-0042"))
                    .isEqualTo(new PaymentLookupObservation.Unavailable("CancellationException", PaymentLookupObservation.FailureKind.TIMEOUT));
                case "ORDER" -> assertThat(gateway.lookupByOrder("LP-ORD-0000000042"))
                    .isEqualTo(new PaymentLookupObservation.Unavailable("CancellationException", PaymentLookupObservation.FailureKind.TIMEOUT));
                default -> throw new IllegalArgumentException(operation);
            }
        }
        verify(client, times(1)).<InputStream>sendAsync(any(HttpRequest.class), any());
    }
}
