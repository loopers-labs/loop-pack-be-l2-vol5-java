package com.loopers.order.infrastructure.payment.toss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.PaymentGateway.CancellationRequest;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** 승인 HTTP 계약용 대역. 결제 원장이나 주문·재고 처리는 구현하지 않는다. */
public final class TossPgFixture implements AutoCloseable {

    public static final String SECRET_KEY = "local-secret";
    public static final ApprovalRequest SUCCESS_REQUEST =
        new ApprovalRequest("test-payment-success", "LP-ORD-SUCCESS", 7000, "test-attempt-success");
    public static final ApprovalRequest REJECTED_REQUEST =
        new ApprovalRequest("test-payment-rejected", "LP-ORD-REJECTED", 7000, "test-attempt-rejected");
    public static final ApprovalRequest TIMEOUT_REQUEST =
        new ApprovalRequest("test-payment-timeout", "LP-ORD-TIMEOUT", 7000, "test-attempt-timeout");
    public static final ApprovalRequest SERVER_ERROR_REQUEST =
        new ApprovalRequest("test-payment-server-error", "LP-ORD-SERVER-ERROR", 7000, "test-attempt-server-error");
    private static final String APPROVAL_PATH = "/v1/payments/confirm";
    private static final String PAYMENT_PATH = "/v1/payments/%s";
    private static final String CANCEL_PATH = "/v1/payments/%s/cancel";
    private static final Set<String> ERROR_CODES = Set.of(
        "ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST",
        "PROVIDER_ERROR", "INVALID_REQUEST"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final WireMockServer server = new WireMockServer(wireMockConfig()
        .bindAddress("127.0.0.1").dynamicPort());

    public TossPgFixture() {
        server.start();
    }

    /** 합의한 승인 케이스를 함께 등록한다. 클라이언트 응답 대기는 3초보다 짧게 설정한다. */
    public static TossPgFixture withScenarios() {
        var pg = new TossPgFixture();
        try {
            var approvedAt = OffsetDateTime.parse("2026-10-21T15:00:00+09:00");
            pg.approve(SUCCESS_REQUEST, approvedAt);
            pg.reject(REJECTED_REQUEST);
            pg.delayedApproval(TIMEOUT_REQUEST, approvedAt, Duration.ofSeconds(3));
            pg.serverError(SERVER_ERROR_REQUEST);
            return pg;
        } catch (RuntimeException exception) {
            pg.close();
            throw exception;
        }
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /** 같은 멱등키·본문으로 재요청해도 동일한 성공 응답을 반환한다. */
    public void approve(ApprovalRequest request, OffsetDateTime approvedAt) {
        delayedApproval(request, approvedAt, Duration.ZERO);
    }

    /** 클라이언트의 응답 대기 제한보다 긴 지연을 지정해 타임아웃을 재현한다. */
    public void delayedApproval(ApprovalRequest request, OffsetDateTime approvedAt, Duration delay) {
        respond(request, 200, approvalBody(request, approvedAt), delay);
    }

    private String approvalBody(ApprovalRequest request, OffsetDateTime approvedAt) {
        return json(Map.of(
            "paymentKey", request.paymentKey(),
            "orderId", request.providerOrderId(),
            "totalAmount", request.amount(),
            "status", "DONE",
            "approvedAt", approvedAt.toString()
        ));
    }

    /** 같은 멱등키·본문으로 재요청해도 동일한 승인 거절 응답을 반환한다. */
    public void reject(ApprovalRequest request) {
        respond(request, 403, json(Map.of(
            "code", "REJECT_CARD_PAYMENT",
            "message", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."
        )), Duration.ZERO);
    }

    public void serverError(ApprovalRequest request) {
        respond(request, 500, json(Map.of(
            "code", "FAILED_INTERNAL_SYSTEM_PROCESSING",
            "message", "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."
        )), Duration.ZERO);
    }

    public void cancel(CancellationRequest request) {
        respondToCancellation(request, 200, json(Map.of(
            "paymentKey", request.paymentKey(),
            "orderId", REQUEST_ORDER_ID,
            "totalAmount", 7000,
            "status", "CANCELED",
            "cancels", List.of(Map.of(
                "cancelAmount", 7000,
                "cancelReason", request.cancelReason(),
                "canceledAt", "2026-10-21T15:01:00+09:00",
                "transactionKey", "test-cancel-42",
                "cancelStatus", "DONE"
            ))
        )));
    }

    public void cancellationServerError(CancellationRequest request) {
        respondToCancellation(request, 500, json(Map.of(
            "code", "FAILED_INTERNAL_SYSTEM_PROCESSING",
            "message", "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."
        )));
    }

    private static final String REQUEST_ORDER_ID = "LP-ORD-0000000042";

    private void respondToCancellation(CancellationRequest request, int status, String body) {
        server.stubFor(cancellationRequest(request)
            .willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private MappingBuilder cancellationRequest(CancellationRequest request) {
        return post(urlEqualTo(CANCEL_PATH.formatted(request.paymentKey())))
            .withBasicAuth(SECRET_KEY, "")
            .withHeader("Idempotency-Key", equalTo(request.idempotencyKey()))
            .withHeader("Content-Type", matching("application/json.*"))
            .withRequestBody(equalToJson(json(Map.of("cancelReason", request.cancelReason()))));
    }

    public List<LoggedRequest> cancellationRequests(CancellationRequest request) {
        return server.findAll(postRequestedFor(urlEqualTo(CANCEL_PATH.formatted(request.paymentKey()))));
    }

    public void payment(String paymentKey, int status, String body) {
        server.stubFor(get(urlEqualTo(PAYMENT_PATH.formatted(paymentKey)))
            .withBasicAuth(SECRET_KEY, "")
            .willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    public List<LoggedRequest> paymentRequests(String paymentKey) {
        return server.findAll(getRequestedFor(urlEqualTo(PAYMENT_PATH.formatted(paymentKey))));
    }

    public void error(ApprovalRequest request, String code, String message) {
        if (!ERROR_CODES.contains(code)) {
            throw new IllegalArgumentException("Unsupported fixture error: " + code);
        }
        respond(request, 400, json(Map.of("code", code, "message", message)), Duration.ZERO);
    }

    /** 깨진 JSON·필드 불일치·계약 밖 HTTP 응답을 주입하는 테스트에 사용한다. */
    public void respond(ApprovalRequest request, int httpStatus, String body, Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Response delay must not be negative");
        }
        int delayMillis = Math.toIntExact(delay.toMillis());
        // 서로 다른 결제 케이스를 함께 등록할 수 있도록 기존 매핑을 유지한다.
        server.stubFor(approvalRequest(request)
            .willReturn(aResponse().withStatus(httpStatus)
                .withHeader("Content-Type", "application/json")
                .withBody(body).withFixedDelay(delayMillis)));
    }

    private MappingBuilder approvalRequest(ApprovalRequest request) {
        return post(urlEqualTo(APPROVAL_PATH))
            .withBasicAuth(SECRET_KEY, "")
            .withHeader("Idempotency-Key", equalTo(request.idempotencyKey()))
            .withHeader("Content-Type", matching("application/json.*"))
            .withRequestBody(equalToJson(json(Map.of(
                "paymentKey", request.paymentKey(),
                "orderId", request.providerOrderId(),
                "amount", request.amount()
            ))));
    }

    public List<LoggedRequest> approvalRequests() {
        return server.findAll(postRequestedFor(urlEqualTo(APPROVAL_PATH)));
    }

    public List<LoggedRequest> unmatchedRequests() {
        return server.findAllUnmatchedRequests();
    }

    @Override
    public void close() {
        server.stop();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize fixture data", exception);
        }
    }
}
