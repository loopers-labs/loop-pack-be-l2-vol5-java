package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentObservation.UnknownCause;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.RequestFailedException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.util.DeadlineTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * PG HTTP 경계. 요청 한 줄과 결과 한 줄을 남겨, 응답을 받지 못한 것과 받은 뒤 내부에서 실패한 것을 구분할 수 있게 한다.
 * 카드 정보는 남기지 않는다. 우리 쪽 주문은 providerOrderId로 찾는다.
 */
@Slf4j
public class TestPgPaymentAdapter implements PaymentProvider, AutoCloseable {

    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final DefaultResponseErrorHandler LOOKUP_ERRORS = new DefaultResponseErrorHandler();

    private final CloseableHttpClient httpClient;
    private final RestClient client;
    private final RestClient lookupClient;
    private final String callbackUrl;

    /** 조회는 다시 물을 수 있으므로 생성보다 짧게 기다린다. 두 클라이언트는 같은 연결 풀을 쓴다. */
    TestPgPaymentAdapter(String baseUrl, String callbackUrl, Duration connectTimeout, Duration readTimeout,
                         Duration lookupReadTimeout) {
        this(baseUrl, callbackUrl, connectTimeout, readTimeout, lookupReadTimeout, HttpClients.custom());
    }

    /** HTTP 처리 규칙은 그대로 두고, 실험에서 연결 풀의 크기와 공유 여부를 정할 수 있다. */
    TestPgPaymentAdapter(String baseUrl, String callbackUrl, Duration connectTimeout, Duration readTimeout,
                         Duration lookupReadTimeout, HttpClientBuilder httpClientBuilder) {
        if (!isPositive(connectTimeout) || !isPositive(readTimeout) || !isPositive(lookupReadTimeout)) {
            throw new IllegalArgumentException("PG timeout은 1ms 이상이어야 합니다.");
        }
        this.callbackUrl = callbackUrl;
        httpClient = httpClientBuilder.disableAutomaticRetries().disableRedirectHandling().build();
        client = restClient(baseUrl, connectTimeout, readTimeout);
        lookupClient = restClient(baseUrl, connectTimeout, lookupReadTimeout);
    }

    private RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(connectTimeout);
        factory.setConnectionRequestTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("X-USER-ID", "135135").build();
    }

    private static boolean isPositive(Duration timeout) {
        return timeout != null && timeout.toMillis() > 0;
    }

    @Override
    public PaymentObservation create(PaymentRequest request) {
        log.info("PG 요청: op=create providerOrderId={} amount={}", request.providerOrderId(), request.amount());
        return answered("create", "providerOrderId=" + request.providerOrderId(), () -> sendCreate(request));
    }

    private Answer sendCreate(PaymentRequest request) {
        String providerOrderId = request.providerOrderId();
        var body = new CreateBody(providerOrderId, request.cardType(), request.cardNo(), request.amount(), callbackUrl);
        try {
            var response = client.post().uri("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toEntity(JsonNode.class);
            if (response.getStatusCode().value() != 202) {
                return new Answer(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), null);
            }
            // 주문 번호·금액 대조는 조회 결과와 같이 TX B가 맡아 불일치 근거를 이력에 남긴다.
            PaymentTransaction transaction = toTransaction(response.getBody());
            if (transaction.status() != PaymentTransaction.Status.PENDING) {
                return new Answer(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), null);
            }
            return new Answer(PaymentObservation.observed(List.of(transaction)), null, response.getStatusCode().value());
        } catch (HttpClientErrorException.UnprocessableEntity exception) {
            boolean proven = isProvenRejection(exception.getResponseBodyAsString(), providerOrderId, request.amount());
            return new Answer(proven ? PaymentObservation.rejected()
                : PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), exception);
        } catch (ResourceAccessException exception) {
            return new Answer(endedBeforeSending(exception)
                ? PaymentObservation.notSent() : PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE), exception);
        } catch (HttpServerErrorException exception) {
            return new Answer(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE), exception);
        } catch (RestClientException | IllegalArgumentException exception) {
            return new Answer(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), exception);
        }
    }

    /** 연결 풀에서 연결을 빌리지 못한 조회는 PG에 닿지 않았으므로 PG의 일시적 장애와 구분한다. */
    private static PaymentObservation lookupNotAnswered(ResourceAccessException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (isConnectionAcquisitionTimeout(cause)) {
                return PaymentObservation.unknown(UnknownCause.CONNECTION_UNAVAILABLE);
            }
        }
        return PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE);
    }

    /** lease future 자체가 만료되면 HttpClient는 RequestFailedException으로 감싼다. 연결 수립 이후 timeout과 구분한다. */
    static boolean isConnectionAcquisitionTimeout(Throwable cause) {
        return cause instanceof ConnectionRequestTimeoutException
            || (cause instanceof RequestFailedException && cause.getCause() instanceof DeadlineTimeoutException);
    }

    /**
     * 요청 본문이 나가기 전, 연결 단계에서 끝났는지 본다. 연결을 빌리지 못함·연결 거부·연결 시간 초과만 해당한다.
     * 응답 timeout은 요청이 PG에 닿았을 수 있어 해당하지 않는다.
     */
    private static boolean endedBeforeSending(ResourceAccessException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (isConnectionAcquisitionTimeout(cause) || cause instanceof ConnectException
                || cause instanceof ConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 코드·거래 미생성 boolean·보낸 주문 번호·금액이 모두 맞을 때만 효과 0건의 증거로 본다. */
    private static boolean isProvenRejection(String body, String providerOrderId, long amount) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            JsonNode created = node.path("transactionCreated");
            return REJECTED_BEFORE_CREATE.equals(node.path("code").textValue())
                && created.isBoolean() && !created.booleanValue()
                && providerOrderId.equals(node.path("orderId").textValue())
                && node.path("amount").isIntegralNumber() && node.path("amount").canConvertToLong()
                && node.path("amount").longValue() == amount;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    @Override
    public PaymentObservation findByTransactionKey(String transactionKey) {
        log.info("PG 요청: op=lookup transactionKey={}", transactionKey);
        return answered("lookup", "transactionKey=" + transactionKey, () -> sendLookup(transactionKey));
    }

    private Answer sendLookup(String transactionKey) {
        var request = lookupClient.get().uri("/api/v1/payments/{transactionKey}", transactionKey);
        return readLookup(request, false);
    }

    @Override
    public PaymentObservation findByOrderId(String providerOrderId) {
        log.info("PG 요청: op=lookup providerOrderId={}", providerOrderId);
        return answered("lookup", "providerOrderId=" + providerOrderId, () -> sendLookupByOrderId(providerOrderId));
    }

    private Answer sendLookupByOrderId(String providerOrderId) {
        var request = lookupClient.get()
            .uri(builder -> builder.path("/api/v1/payments").queryParam("orderId", "{orderId}").build(providerOrderId));
        return readLookup(request, true);
    }

    private Answer readLookup(RestClient.RequestHeadersSpec<?> request, boolean byOrderId) {
        try {
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                if (LOOKUP_ERRORS.hasError(response)) {
                    LOOKUP_ERRORS.handleError(sent.getURI(), sent.getMethod(), response);
                }
                if (status != 200) {
                    return new Answer(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), null, status);
                }
                try {
                    JsonNode body = response.bodyTo(JsonNode.class);
                    List<PaymentTransaction> transactions = byOrderId ? toTransactions(body) : List.of(toTransaction(body));
                    return new Answer(PaymentObservation.observed(transactions), null, status);
                } catch (RestClientException | IllegalArgumentException exception) {
                    return new Answer(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), exception, status);
                }
            });
        } catch (HttpClientErrorException.NotFound exception) {
            PaymentObservation observation = byOrderId ? PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE)
                : PaymentObservation.observed(List.of());
            return new Answer(observation, exception);
        } catch (ResourceAccessException exception) {
            return new Answer(lookupNotAnswered(exception), exception);
        } catch (HttpServerErrorException exception) {
            return new Answer(PaymentObservation.unknown(UnknownCause.TEMPORARY_FAILURE), exception);
        } catch (RestClientException | IllegalArgumentException exception) {
            return new Answer(PaymentObservation.unknown(UnknownCause.UNEXPECTED_RESPONSE), exception);
        }
    }

    private static List<PaymentTransaction> toTransactions(JsonNode body) {
        if (body == null || !body.isArray()) {
            throw new IllegalArgumentException("PG 주문 조회 응답은 거래 목록이어야 합니다.");
        }
        List<PaymentTransaction> transactions = new ArrayList<>();
        for (JsonNode transaction : body) {
            transactions.add(toTransaction(transaction));
        }
        return transactions;
    }

    /**
     * 결과 한 줄을 남긴다. 받은 거래는 key·PG가 준 주문 번호·상태·금액을, 받지 못했으면 이유와 가장 안쪽 예외 이름(timeout·연결 끊김 구분)을 쓴다.
     * 결과를 확인하지 못한 경우만 WARN이다.
     */
    private static PaymentObservation answered(String op, String target, Supplier<Answer> call) {
        long started = System.nanoTime();
        Answer answer = call.get();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        PaymentObservation observation = answer.observation();
        String responseFields = answer.httpStatus() == null ? target : target + " httpStatus=" + answer.httpStatus();
        if (observation.kind() == PaymentObservation.Kind.OBSERVED || observation.kind() == PaymentObservation.Kind.REJECTED) {
            String transactions = observation.transactions().stream()
                .map(t -> t.transactionKey() + ":" + t.providerOrderId() + ":" + t.status() + ":" + t.amount()).toList().toString();
            log.info("PG 응답: op={} {} result={} transactions={} elapsedMs={}",
                op, responseFields, observation.kind(), transactions, elapsedMs);
        } else if (answer.httpStatus() != null) {
            log.warn("PG 응답: op={} {} result={} cause={} error={} elapsedMs={}",
                op, responseFields, observation.kind(), observation.unknownCause(), rootName(answer.error()), elapsedMs);
        } else {
            log.warn("PG 응답 없음: op={} {} result={} cause={} error={} elapsedMs={}",
                op, target, observation.kind(), observation.unknownCause(), rootName(answer.error()), elapsedMs);
        }
        return observation;
    }

    private static String rootName(Throwable error) {
        if (error == null) {
            return "none";
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static PaymentTransaction toTransaction(JsonNode body) {
        if (body == null || !body.path("transactionKey").isTextual() || !body.path("orderId").isTextual()
            || !body.path("amount").isIntegralNumber() || !body.path("amount").canConvertToLong()
            || !body.path("status").isTextual()) {
            throw new IllegalArgumentException("PG 응답 형식이 올바르지 않습니다.");
        }
        return new PaymentTransaction(body.path("transactionKey").asText(), body.path("orderId").asText(),
            body.path("amount").longValue(), PaymentTransaction.Status.valueOf(body.path("status").asText()));
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private record Answer(PaymentObservation observation, Throwable error, Integer httpStatus) {
        private Answer(PaymentObservation observation, Throwable error) {
            this(observation, error, error instanceof HttpStatusCodeException response ? response.getStatusCode().value() : null);
        }
    }

    private record CreateBody(String orderId, String cardType, String cardNo, long amount, String callbackUrl) {
        @Override
        public String toString() {
            return "CreateBody[orderId=" + orderId + ", amount=" + amount + ", card=REDACTED]";
        }
    }
}
