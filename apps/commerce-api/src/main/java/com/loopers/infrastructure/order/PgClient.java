package com.loopers.infrastructure.order;

import com.loopers.application.order.PaymentGateway;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.loopers.application.order.PgOrderId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;

/**
 * PaymentGateway 구현. PG POST /api/v1/payments 를 RestClient 로 부른다. 예외를 던지지 않고 결과로 바꾼다.
 * 2xx: 응답 status(PENDING·SUCCESS·FAILED) 그대로 / 4xx: FAILED / 5xx·read timeout: UNKNOWN / 연결 실패·connect timeout: NOT_SENT.
 * 결제 요청은 재시도하지 않는다 (이중 결제 방지). 카드번호는 로그에 마스킹한다.
 */
@Slf4j
@Component
public class PgClient implements PaymentGateway {
    private final RestClient restClient;
    private final PgProperties properties;

    public PgClient(RestClient.Builder builder, PgProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @Override
    public PaymentGatewayResult request(Long userId, Long orderId, String cardType, String cardNo, Long amount) {
        PaymentRequest body = new PaymentRequest(PgOrderId.of(orderId), cardType, cardNo, amount, properties.callbackUrl());
        try {
            return restClient.post()
                .uri("/api/v1/payments")
                .header("X-USER-ID", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return toResult(orderId, response.bodyTo(PaymentResponse.class));
                    }
                    if (status.is4xxClientError()) {
                        log.warn("PG 결제 거절 [orderId = {}, status = {}, card = {}]", orderId, status, mask(cardNo));
                        return new PaymentGatewayResult(Outcome.FAILED, null, null);
                    }
                    log.warn("PG 응답 오류, 결과 모름 [orderId = {}, status = {}]", orderId, status);
                    return PaymentGatewayResult.unknown();
                });
        } catch (ResourceAccessException e) {
            if (isNotSent(e)) {
                log.warn("PG 연결 실패, 요청 미전송 [orderId = {}]", orderId, e);
                return PaymentGatewayResult.notSent();
            }
            log.warn("PG 응답 없음, 결과 모름 [orderId = {}]", orderId, e);
            return PaymentGatewayResult.unknown();
        } catch (RestClientException e) {
            log.warn("PG 응답 처리 실패, 결과 모름 [orderId = {}]", orderId, e);
            return PaymentGatewayResult.unknown();
        }
    }

    private PaymentGatewayResult toResult(Long orderId, PaymentResponse response) {
        if (response == null || response.status() == null) {
            log.warn("PG 응답 본문 없음, 결과 모름 [orderId = {}]", orderId);
            return PaymentGatewayResult.unknown();
        }
        Outcome outcome = switch (response.status()) {
            case "PENDING" -> Outcome.PENDING;
            case "SUCCESS" -> Outcome.SUCCESS;
            case "FAILED" -> Outcome.FAILED;
            default -> {
                log.warn("PG 응답 status 를 알 수 없음, 결과 모름 [orderId = {}, status = {}]", orderId, response.status());
                yield Outcome.UNKNOWN;
            }
        };
        return new PaymentGatewayResult(outcome, response.transactionKey(), response.amount());
    }

    /** 요청이 PG 에 닿지 않았음이 확실한 경우: 연결 거부·connect timeout. */
    private static boolean isNotSent(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String mask(String cardNo) {
        if (cardNo == null || cardNo.length() < 4) {
            return "****";
        }
        return "****-" + cardNo.substring(cardNo.length() - 4);
    }

    record PaymentRequest(String orderId, String cardType, String cardNo, Long amount, String callbackUrl) {
    }

    record PaymentResponse(String transactionKey, String orderId, Long amount, String status) {
    }
}
