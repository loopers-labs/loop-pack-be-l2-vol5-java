package com.loopers.testpg.infrastructure;

import com.loopers.testpg.application.PaymentCallback;
import com.loopers.testpg.config.TestPgProperties;
import com.loopers.testpg.domain.Payment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Component
public class HttpPaymentCallback implements PaymentCallback {
    private final RestClient client;

    public HttpPaymentCallback(RestClient.Builder builder, TestPgProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.callbackConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.callbackReadTimeout());
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public void send(Payment payment) {
        client.post().uri(payment.getCallbackUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .body(CallbackBody.from(payment))
            .retrieve().toBodilessEntity();
    }

    private record CallbackBody(String providerOrderId, String transactionKey, String status) {
        static CallbackBody from(Payment payment) {
            return new CallbackBody(payment.getOrderId(), payment.getTransactionKey(), payment.getStatus().name());
        }
    }
}
