package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.CreatePaymentRequest;
import com.loopers.application.payment.CreateResult;
import com.loopers.application.payment.LookupRetry;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentGatewayUnavailableException;
import com.loopers.domain.payment.PaymentObservation;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 로컬 테스트 PG HTTP adapter (docs/week5/pg-contract.md).
 *
 * - create는 재시도하지 않는다. 하위 JDK HttpClient도 멱등하지 않은 요청을 기본으로 다시 보내지 않는다(INV-005, D-030).
 * - create 응답은 정확한 조건의 422만 효과 0건으로 보고, 나머지는 timeout을 포함해 전부 UNKNOWN이다(INV-004, D-025).
 * - 조회 재시도는 경로별 횟수(LookupRetry)만큼 이 안에서 한다. circuit breaker가 그 바깥을 감싼다 — 재시도까지 끝난 조회 한 번이
 *   표본 하나다. 회로가 열려 있으면 PG를 부르지 않고 "물어보지 못함"으로 돌려준다(D-028, D-029). create에는 걸지 않는다.
 * - 요청·응답 본문을 로그에 남기지 않는다 — 카드 번호가 들어 있다.
 */
@Component
public class PgPaymentGateway implements PaymentGateway {

    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String USER_HEADER = "X-USER-ID";
    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final double RETRY_WAIT_MULTIPLIER = 2.0;

    private final PgClientSettings settings;
    private final ObjectMapper objectMapper;
    private final RestClient createClient;
    private final RestClient lookupClient;
    private final Map<LookupRetry, Retry> retries = new EnumMap<>(LookupRetry.class);
    private final CircuitBreaker lookupCircuitBreaker;

    @Autowired
    public PgPaymentGateway(
        ObjectMapper objectMapper,
        @Value("${pg.base-url}") String baseUrl,
        @Value("${pg.user-id}") String userId,
        @Value("${pg.callback-url}") String callbackUrl,
        @Value("${pg.create.connect-timeout:1s}") Duration createConnectTimeout,
        @Value("${pg.create.read-timeout:2s}") Duration createReadTimeout,
        @Value("${pg.lookup.connect-timeout:1s}") Duration lookupConnectTimeout,
        @Value("${pg.lookup.read-timeout:1s}") Duration lookupReadTimeout,
        @Value("${pg.lookup.retry-initial-wait:100ms}") Duration lookupRetryInitialWait,
        CircuitBreaker pgLookupCircuitBreaker
    ) {
        this(new PgClientSettings(baseUrl, userId, callbackUrl, createConnectTimeout, createReadTimeout,
            lookupConnectTimeout, lookupReadTimeout, lookupRetryInitialWait), objectMapper, pgLookupCircuitBreaker);
    }

    PgPaymentGateway(PgClientSettings settings, ObjectMapper objectMapper, CircuitBreaker lookupCircuitBreaker) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.lookupCircuitBreaker = lookupCircuitBreaker;
        this.createClient = restClient(settings.baseUrl(), settings.createConnectTimeout(), settings.createReadTimeout());
        this.lookupClient = restClient(settings.baseUrl(), settings.lookupConnectTimeout(), settings.lookupReadTimeout());
        for (LookupRetry policy : LookupRetry.values()) {
            // 사이 대기 100ms → 200ms. 이 예외만 다시 시도한다 — 404·빈 목록은 정상 응답이라 다시 묻지 않는다.
            RetryConfig config = RetryConfig.custom()
                .maxAttempts(policy.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(settings.lookupRetryInitialWait(), RETRY_WAIT_MULTIPLIER))
                .retryExceptions(PaymentGatewayUnavailableException.class)
                .build();
            retries.put(policy, Retry.of("pg-lookup-" + policy.name(), config));
        }
    }

    private static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public String providerOrderIdOf(Long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    @Override
    public CreateResult create(CreatePaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", request.providerOrderId());
        body.put("cardType", request.cardType());
        body.put("cardNo", request.cardNo());
        body.put("amount", request.amount());
        body.put("callbackUrl", settings.callbackUrl());
        try {
            return createClient.post()
                .uri(PAYMENTS_PATH)
                .header(USER_HEADER, settings.userId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((httpRequest, response) -> interpretCreate(response.getStatusCode().value(), readBody(response), request));
        } catch (RuntimeException e) {
            // 연결 실패와 응답 timeout을 구분하지 않는다(D-025) — 둘 다 거래가 생겼는지 모른다.
            // RestClientException만 잡지 않는다: read timeout 경합에서 JDK client의 CancellationException이 그대로 새어 나온
            // 적이 있다(PgPaymentGatewayContractTest). 어떤 예외든 "모른다"로 두는 게 INV-004를 지키는 쪽이다.
            return new CreateResult.Unknown(e.getClass().getSimpleName());
        }
    }

    private CreateResult interpretCreate(int status, String rawBody, CreatePaymentRequest request) {
        if (status == 202) {
            PgTransaction accepted = parse(rawBody, PgTransaction.class);
            if (accepted != null && hasText(accepted.transactionKey())
                && request.providerOrderId().equals(accepted.orderId())
                && Long.valueOf(request.amount()).equals(accepted.amount())) {
                return new CreateResult.Accepted(accepted.transactionKey());
            }
            return new CreateResult.Unknown("202 응답의 거래 식별·금액을 확인할 수 없음");
        }
        if (status == 422) {
            PgRejection rejection = parse(rawBody, PgRejection.class);
            if (rejection != null && REJECTED_BEFORE_CREATE.equals(rejection.code())
                && Boolean.FALSE.equals(rejection.transactionCreated())
                && request.providerOrderId().equals(rejection.orderId())
                && Long.valueOf(request.amount()).equals(rejection.amount())) {
                return new CreateResult.RejectedNoEffect();
            }
            return new CreateResult.Unknown("효과 0건이 확인되지 않은 422");
        }
        return new CreateResult.Unknown("HTTP " + status);
    }

    @Override
    public Optional<PaymentObservation> findByTransactionKey(String transactionKey, LookupRetry retry) {
        return withRetry(retry, () -> lookup(() -> lookupClient.get()
            .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
            .header(USER_HEADER, settings.userId())
            .exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) {
                    return Optional.<PaymentObservation>empty();
                }
                requireOk(status);
                return Optional.of(toObservation(parseOrThrow(readBody(response), PgTransaction.class)));
            })));
    }

    @Override
    public List<PaymentObservation> findByProviderOrderId(String providerOrderId, LookupRetry retry) {
        return withRetry(retry, () -> lookup(() -> lookupClient.get()
            // 값을 URI 변수로 넘겨야 "&"·공백 같은 문자까지 인코딩된다(queryParam에 값을 바로 넣으면 템플릿으로 취급된다).
            .uri(uriBuilder -> uriBuilder.path(PAYMENTS_PATH).queryParam("orderId", "{orderId}").build(providerOrderId))
            .header(USER_HEADER, settings.userId())
            .exchange((httpRequest, response) -> {
                requireOk(response.getStatusCode().value());
                PgTransaction[] transactions = parseOrThrow(readBody(response), PgTransaction[].class);
                return Arrays.stream(transactions).map(this::toObservation).toList();
            })));
    }

    private <T> T withRetry(LookupRetry policy, Supplier<T> call) {
        Supplier<T> retried = Retry.decorateSupplier(retries.get(policy), call);
        try {
            return lookupCircuitBreaker.executeSupplier(retried);
        } catch (CallNotPermittedException e) {
            // 회로가 열려 PG를 부르지 않았다 — "거래 없음"이 아니라 "물어보지 못함"이다(INV-004).
            throw new PaymentGatewayUnavailableException("PG 조회 회로 열림", e);
        }
    }

    private static <T> T lookup(Supplier<T> call) {
        try {
            return call.get();
        } catch (PaymentGatewayUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // create와 같은 이유로 예외 종류를 가리지 않는다 — 물어보지 못했으면 모두 "확인 불가"이고, 재시도 대상이다.
            throw new PaymentGatewayUnavailableException("PG 조회 실패: " + e.getClass().getSimpleName(), e);
        }
    }

    private static void requireOk(int status) {
        if (status != 200) {
            throw new PaymentGatewayUnavailableException("PG 조회 응답 HTTP " + status);
        }
    }

    private PaymentObservation toObservation(PgTransaction transaction) {
        try {
            return new PaymentObservation(
                transaction.orderId(),
                transaction.transactionKey(),
                PaymentObservation.Status.valueOf(transaction.status()),
                transaction.amount()
            );
        } catch (RuntimeException e) {
            // 필드가 비었거나 모르는 상태값이면 관찰로 인정하지 않는다 — 결과를 정하지 않고 "확인 불가"로 둔다.
            throw new PaymentGatewayUnavailableException("PG 조회 응답을 해석할 수 없음", e);
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private <T> T parse(String rawBody, Class<T> type) {
        if (!hasText(rawBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, type);
        } catch (IOException e) {
            return null;
        }
    }

    private <T> T parseOrThrow(String rawBody, Class<T> type) {
        T parsed = parse(rawBody, type);
        if (parsed == null) {
            throw new PaymentGatewayUnavailableException("PG 조회 응답을 해석할 수 없음");
        }
        return parsed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PgTransaction(String transactionKey, String orderId, Long amount, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PgRejection(String code, String orderId, Long amount, Boolean transactionCreated) {}
}
