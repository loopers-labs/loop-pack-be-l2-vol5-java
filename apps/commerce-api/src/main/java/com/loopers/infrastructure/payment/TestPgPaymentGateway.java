package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransaction;
import com.loopers.application.payment.port.PaymentTransactionLookup;
import com.loopers.application.payment.port.PaymentTransactionStatus;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.domain.common.Money;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 응답 본문은 카드 번호를 되돌려줄 수 있으므로 결과·로그에는 상태 코드·원인 타입만 남긴다.
@Slf4j
@Component
public class TestPgPaymentGateway implements PaymentGateway {
    static final String PROVIDER_ID = "test-pg";
    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String REJECT_CODE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int ACCEPTED = 202;
    private static final int NOT_FOUND = 404;
    private static final int OK = 200;
    private static final int UNPROCESSABLE = 422;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TestPgProperties properties;

    public TestPgPaymentGateway(TestPgProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // JDK HttpClient는 POST를 자동 재전송하지 않는다(연결 수립 실패만 재연결). 리다이렉트도 따르지 않는다.
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl().toString())
            .requestFactory(requestFactory)
            .defaultHeader(USER_ID_HEADER, properties.userId())
            .build();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(requestBody(command));
        } catch (JsonProcessingException e) {
            // 보내기 전 실패라 미발송이다. 예외 메시지에 카드 번호가 섞일 수 있어 원인 타입만 남긴다.
            return notSent("요청 본문 생성 실패: " + e.getClass().getSimpleName(), command);
        }
        try {
            PaymentCreateResult result = restClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> classifyCreate(response.getStatusCode().value(),
                    response.getBody().readAllBytes(), command));
            if (result instanceof PaymentCreateResult.Unknown unknown) {
                log.warn("테스트 PG 결제 생성 결과를 알 수 없습니다. orderId={}, reason={}", command.providerOrderId(), unknown.reason());
            }
            return result;
        } catch (RestClientException e) {
            String reason = causeSummary(e);
            if (failedBeforeSending(e)) {
                return notSent(reason, command);
            }
            log.warn("테스트 PG 결제 생성 결과를 알 수 없습니다. orderId={}, reason={}", command.providerOrderId(), reason);
            return new PaymentCreateResult.Unknown(reason);
        } catch (RuntimeException e) {
            // 요청이 나갔는지 판단할 수 없으므로 결과 모름이다.
            String reason = e.getClass().getSimpleName();
            log.warn("테스트 PG 결제 생성 결과를 알 수 없습니다. orderId={}, reason={}", command.providerOrderId(), reason);
            return new PaymentCreateResult.Unknown(reason);
        }
    }

    @Override
    public PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey) {
        return findByOrderId(providerOrderId);
    }

    PaymentTransactionLookup findByTransactionKey(String transactionKey) {
        try {
            return restClient.get()
                .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == NOT_FOUND) {
                        return new PaymentTransactionLookup.NotFound();
                    }
                    if (status != OK) {
                        return lookupFailed("HTTP " + status);
                    }
                    return readTree(response.getBody().readAllBytes())
                        .flatMap(this::toTransaction)
                        .filter(transaction -> transaction.transactionKey().equals(transactionKey))
                        .<PaymentTransactionLookup>map(PaymentTransactionLookup.Found::new)
                        .orElseGet(() -> lookupFailed("응답 형식 오류"));
                });
        } catch (RestClientException e) {
            return lookupFailed(causeSummary(e));
        } catch (RuntimeException e) {
            return lookupFailed(e.getClass().getSimpleName());
        }
    }

    PaymentTransactionsLookup findByOrderId(String providerOrderId) {
        try {
            return restClient.get()
                .uri(uri -> uri.path(PAYMENTS_PATH).queryParam("orderId", "{orderId}").build(providerOrderId))
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status != OK) {
                        return listFailed("HTTP " + status);
                    }
                    return readTree(response.getBody().readAllBytes())
                        .flatMap(this::toTransactions)
                        .<PaymentTransactionsLookup>map(transactions -> new PaymentTransactionsLookup.Found(
                            transactions, PaymentTransactionsLookup.Coverage.ALL))
                        .orElseGet(() -> listFailed("응답 형식 오류"));
                });
        } catch (RestClientException e) {
            return listFailed(causeSummary(e));
        } catch (RuntimeException e) {
            return listFailed(e.getClass().getSimpleName());
        }
    }

    // 연결 수립 실패만 미발송으로 본다. read timeout·연결 후 끊김은 요청이 나갔을 수 있다.
    // HttpConnectTimeoutException은 read timeout 예외(HttpTimeoutException)의 하위 타입이므로 상위 타입으로 판단하면 안 된다.
    static boolean failedBeforeSending(RestClientException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException;
    }

    private Map<String, Object> requestBody(PaymentCreateCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", command.providerOrderId());
        body.put("cardType", command.cardType());
        body.put("cardNo", command.cardNo());
        body.put("amount", command.amount().value());
        body.put("callbackUrl", properties.callbackUrl());
        return body;
    }

    private PaymentCreateResult classifyCreate(int status, byte[] body, PaymentCreateCommand command) {
        if (status == ACCEPTED) {
            return readTree(body)
                .flatMap(this::toTransaction)
                .filter(transaction -> matches(transaction, command))
                .<PaymentCreateResult>map(PaymentCreateResult.Accepted::new)
                .orElseGet(() -> new PaymentCreateResult.Unknown("HTTP 202 응답 형식 오류 또는 요청 값 불일치"));
        }
        if (status == UNPROCESSABLE && isRejectedBeforeCreate(readTree(body), command)) {
            return new PaymentCreateResult.RejectedNoEffect(REJECT_CODE);
        }
        return new PaymentCreateResult.Unknown("HTTP " + status);
    }

    private boolean isRejectedBeforeCreate(Optional<JsonNode> body, PaymentCreateCommand command) {
        return body.filter(node -> textEquals(node.get("code"), REJECT_CODE))
            .filter(node -> node.path("transactionCreated").isBoolean() && !node.path("transactionCreated").booleanValue())
            .filter(node -> textEquals(node.get("orderId"), command.providerOrderId()))
            .filter(node -> amountOf(node).filter(amount -> amount == command.amount().value()).isPresent())
            .isPresent();
    }

    private Optional<PaymentTransaction> toTransaction(JsonNode node) {
        JsonNode key = node.get("transactionKey");
        JsonNode orderId = node.get("orderId");
        Optional<Long> amount = amountOf(node);
        Optional<PaymentTransactionStatus> status = statusOf(node.get("status"));
        if (key == null || !key.isTextual() || key.asText().isBlank() || orderId == null || !orderId.isTextual()
            || amount.isEmpty() || status.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PaymentTransaction(key.asText(), orderId.asText(), new Money(amount.get()), status.get()));
    }

    private Optional<List<PaymentTransaction>> toTransactions(JsonNode node) {
        if (!node.isArray()) {
            return Optional.empty();
        }
        List<PaymentTransaction> transactions = new ArrayList<>();
        for (JsonNode element : node) {
            Optional<PaymentTransaction> transaction = toTransaction(element);
            if (transaction.isEmpty()) {
                return Optional.empty();
            }
            transactions.add(transaction.get());
        }
        return Optional.of(transactions);
    }

    private static boolean matches(PaymentTransaction transaction, PaymentCreateCommand command) {
        return transaction.providerOrderId().equals(command.providerOrderId()) && transaction.amount().equals(command.amount());
    }

    private static Optional<Long> amountOf(JsonNode node) {
        JsonNode amount = node.get("amount");
        if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 0) {
            return Optional.empty();
        }
        return Optional.of(amount.longValue());
    }

    private static Optional<PaymentTransactionStatus> statusOf(JsonNode status) {
        if (status == null || !status.isTextual()) {
            return Optional.empty();
        }
        for (PaymentTransactionStatus candidate : PaymentTransactionStatus.values()) {
            if (candidate.name().equals(status.asText())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean textEquals(JsonNode node, String expected) {
        return node != null && node.isTextual() && node.asText().equals(expected);
    }

    private Optional<JsonNode> readTree(byte[] body) {
        if (body.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static PaymentCreateResult notSent(String reason, PaymentCreateCommand command) {
        log.warn("테스트 PG에 결제 생성 요청을 보내지 못했습니다. orderId={}, reason={}", command.providerOrderId(), reason);
        return new PaymentCreateResult.NotSent(reason);
    }

    private static PaymentTransactionLookup lookupFailed(String reason) {
        log.warn("테스트 PG 거래 키 조회에 실패했습니다. reason={}", reason);
        return new PaymentTransactionLookup.Failed(reason);
    }

    private static PaymentTransactionsLookup listFailed(String reason) {
        log.warn("테스트 PG 주문 거래 조회에 실패했습니다. reason={}", reason);
        return new PaymentTransactionsLookup.Failed(reason);
    }

    private static String causeSummary(RestClientException exception) {
        Throwable cause = exception.getCause();
        return exception.getClass().getSimpleName() + (cause == null ? "" : "(" + cause.getClass().getSimpleName() + ")");
    }
}
