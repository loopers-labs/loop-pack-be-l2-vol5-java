package com.loopers.infrastructure.order;

import com.loopers.application.order.PaymentGateway;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.loopers.application.order.PgOrderId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * PaymentGateway 구현. PG POST /api/v1/payments·GET /api/v1/payments?orderId= 를 RestClient 로 부른다. 예외를 던지지 않고 결과로 바꾼다.
 * 결제 요청 — 2xx: 응답 status(PENDING·SUCCESS·FAILED) 그대로 / 4xx: FAILED / 5xx·read timeout·본문 수신 끊김: ERROR / 연결 실패·connect timeout: NOT_SENT / 그 밖의 예외: UNKNOWN.
 * read timeout 은 요청 전송부터 본문 수신까지의 마감이다 (JdkClientHttpRequestFactory: 응답이 늦으면 요청 취소, 본문이 늦으면 스트림을 닫는다).
 * 거래 조회 — 2xx: 응답 status 그대로 / 그 밖의 응답·예외: UNKNOWN.
 * 결제 요청은 pg.read-timeout, 거래 조회는 그보다 짧은 pg.lookup-read-timeout 을 쓴다.
 * 결제 요청은 재시도하지 않는다 (이중 결제 방지). 카드번호는 로그에 마스킹한다.
 */
@Slf4j
@Component
public class PgClient implements PaymentGateway {
    private final RestClient paymentClient;
    private final RestClient lookupClient;
    private final PgProperties properties;

    public PgClient(RestClient.Builder builder, PgProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        this.paymentClient = restClient(builder, httpClient, properties.baseUrl(), properties.readTimeout());
        this.lookupClient = restClient(builder, httpClient, properties.baseUrl(), properties.lookupReadTimeout());
        this.properties = properties;
    }

    /** 결제 요청·거래 조회는 read timeout 만 다르다. 연결(HttpClient)은 같이 쓴다. */
    private static RestClient restClient(RestClient.Builder builder, HttpClient httpClient, String baseUrl, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public PaymentGatewayResult request(Long userId, Long orderId, String cardType, String cardNo, Long amount) {
        PaymentRequest body = new PaymentRequest(PgOrderId.of(orderId), cardType, cardNo, amount, properties.callbackUrl());
        try {
            return paymentClient.post()
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
                    log.warn("PG 응답 오류, 실패 처리 [orderId = {}, status = {}]", orderId, status);
                    return PaymentGatewayResult.error();
                });
        } catch (ResourceAccessException e) {
            if (isNotSent(e)) {
                log.warn("PG 연결 실패, 요청 미전송 [orderId = {}]", orderId, e);
                return PaymentGatewayResult.notSent();
            }
            if (isReadTimeout(e)) {
                log.warn("PG 응답 지연, 실패 처리 [orderId = {}]", orderId, e);
                return PaymentGatewayResult.error();
            }
            log.warn("PG 응답 없음, 결과 모름 [orderId = {}]", orderId, e);
            return PaymentGatewayResult.unknown();
        } catch (RestClientException e) {
            if (isBodyCutOff(e)) {
                log.warn("PG 응답 본문 수신 실패(지연·끊김), 실패 처리 [orderId = {}]", orderId, e);
                return PaymentGatewayResult.error();
            }
            log.warn("PG 응답 처리 실패, 결과 모름 [orderId = {}]", orderId, e);
            return PaymentGatewayResult.unknown();
        }
    }

    @Override
    public PaymentGatewayResult find(Long userId, Long orderId) {
        try {
            return lookupClient.get()
                .uri(uri -> uri.path("/api/v1/payments").queryParam("orderId", PgOrderId.of(orderId)).build())
                .header("X-USER-ID", String.valueOf(userId))
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return toResult(orderId, response.bodyTo(PaymentResponse.class));
                    }
                    log.warn("PG 거래 조회 실패, 결과 모름 [orderId = {}, status = {}]", orderId, status);
                    return PaymentGatewayResult.unknown();
                });
        } catch (RestClientException e) {
            log.warn("PG 거래 조회 실패, 결과 모름 [orderId = {}]", orderId, e);
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

    /** read timeout. connect timeout(HttpConnectTimeoutException 도 HttpTimeoutException)은 isNotSent 가 먼저 거른다. */
    private static boolean isReadTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 응답 본문을 받다 끊김. read timeout 이 본문 수신 중에 오면 HttpTimeoutException 이 아니라 스트림이 닫혀 IOException 으로 끝난다.
     * 본문 형식 오류(HttpMessageNotReadableException, 원인 JsonParseException 도 IOException)는 아니다.
     */
    private static boolean isBodyCutOff(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpMessageNotReadableException) {
                return false;
            }
            if (cause instanceof IOException) {
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
