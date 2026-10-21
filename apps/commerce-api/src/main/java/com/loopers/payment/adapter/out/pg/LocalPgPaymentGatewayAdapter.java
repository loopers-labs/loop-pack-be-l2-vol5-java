package com.loopers.payment.adapter.out.pg;

import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PgLookupUnavailableException;
import com.loopers.payment.application.port.out.PgNotSentException;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgRejectedException;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.application.port.out.PgUnknownResultException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 로컬 테스트 PG 어댑터 (plan.md 6·7장). JSON은 감싸지 않은 거래 객체·배열이다.
 *
 * <p>생성 (재시도 없음, ADR-W5-01·03)
 * <ul>
 *   <li>4xx → PgRejectedException (거래 0건). 회로 실패로 세지 않는다 (yml ignore-exceptions)</li>
 *   <li>연결 거부·연결 timeout, 회로 OPEN → PgNotSentException (요청이 나가지 않음)</li>
 *   <li>읽기 timeout·5xx·해석 불가 → PgUnknownResultException (보냈을 수 있음 → 조회로만 확인)</li>
 * </ul>
 * <p>조회: 404 단건 → 빈 값. timeout·5xx·IO는 재시도(yml pgLookup), 끝내 실패하거나 회로 OPEN이면 PgLookupUnavailableException.
 * 404가 아닌 4xx는 재시도하지 않는다.
 *
 * <p>HTTP 클라이언트는 java.net.http(JdkClientHttpRequestFactory)다. HttpURLConnection은 POST를 몰래 다시 보낼 수 있다
 * (sun.net.http.retryPost). TimeLimiter는 쓰지 않는다 — 읽기 timeout이 소켓을 실제로 끊는다 (plan.md 6장).
 * Resilience4j는 어노테이션 대신 코드로 감싼다: 예외를 포트 계약으로 바꾸는 자리와 회로·재시도가 보는 예외를 한곳에서 정하기 위해서다.
 */
@Slf4j
@Component
public class LocalPgPaymentGatewayAdapter implements PaymentGatewayPort {

    static final String CREATE_CIRCUIT = "pgCreate";
    static final String LOOKUP_CIRCUIT = "pgLookup";
    static final String LOOKUP_RETRY = "pgLookup";

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String USER_HEADER = "X-USER-ID";

    private final PgProperties properties;
    private final RestClient createClient;
    private final RestClient lookupClient;
    private final CircuitBreaker createCircuit;
    private final CircuitBreaker lookupCircuit;
    private final Retry lookupRetry;

    public LocalPgPaymentGatewayAdapter(PgProperties properties, CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.createClient = restClient(httpClient, properties.createReadTimeout());
        this.lookupClient = restClient(httpClient, properties.lookupReadTimeout());
        this.createCircuit = circuitBreakerRegistry.circuitBreaker(CREATE_CIRCUIT);
        this.lookupCircuit = circuitBreakerRegistry.circuitBreaker(LOOKUP_CIRCUIT);
        this.lookupRetry = retryRegistry.retry(LOOKUP_RETRY);
    }

    private RestClient restClient(HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
            .requestFactory(factory)
            .baseUrl(properties.baseUrl())
            .defaultHeader(USER_HEADER, properties.userId())
            .build();
    }

    @Override
    public PgTransaction request(PgPaymentRequest request) {
        try {
            return createCircuit.executeSupplier(() -> create(request));
        } catch (CallNotPermittedException e) {
            log.warn("PG 생성 회로가 열려 보내지 않았습니다: {}", request.providerOrderId());
            throw new PgNotSentException("PG 생성 회로가 열려 보내지 않았습니다", e);
        }
    }

    private PgTransaction create(PgPaymentRequest request) {
        PgCreateBody body = new PgCreateBody(
            request.providerOrderId(), request.cardType(), request.cardNo(), request.amount(), properties.callbackUrl());
        try {
            PgTransactionResponse response = createClient.post()
                .uri(PAYMENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PgTransactionResponse.class);
            if (response == null) {
                throw new IllegalStateException("PG 생성 응답 본문이 없습니다");
            }
            return response.toTransaction();
        } catch (HttpClientErrorException e) {
            throw new PgRejectedException("PG가 생성을 거절했습니다: " + e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            log.warn("PG 생성 결과를 모릅니다 (5xx {}): {}", e.getStatusCode().value(), request.providerOrderId());
            throw new PgUnknownResultException("PG 생성 응답이 5xx입니다: " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            if (isNotSent(e)) {
                log.warn("PG에 연결하지 못해 보내지 않았습니다: {}", request.providerOrderId());
                throw new PgNotSentException("PG에 연결하지 못했습니다", e);
            }
            log.warn("PG 생성 결과를 모릅니다 (읽기 실패): {}", request.providerOrderId());
            throw new PgUnknownResultException("PG 생성 응답을 받지 못했습니다", e);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("PG 생성 결과를 모릅니다 (해석 불가): {}", request.providerOrderId());
            throw new PgUnknownResultException("PG 생성 응답을 해석하지 못했습니다", e);
        }
    }

    @Override
    public Optional<PgTransaction> findByTransactionKey(String transactionKey) {
        return lookup(() -> {
            try {
                PgTransactionResponse response = lookupClient.get()
                    .uri(PAYMENTS + "/{transactionKey}", transactionKey)
                    .retrieve()
                    .body(PgTransactionResponse.class);
                if (response == null) {
                    throw new IllegalStateException("PG 조회 응답 본문이 없습니다");
                }
                return Optional.of(response.toTransaction());
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    return Optional.empty();
                }
                throw new PgLookupClientErrorException("PG 조회가 거절됐습니다: " + e.getStatusCode().value(), e);
            }
        });
    }

    @Override
    public List<PgTransaction> findByProviderOrderId(String providerOrderId) {
        return lookup(() -> {
            try {
                PgTransactionResponse[] response = lookupClient.get()
                    .uri(uri -> uri.path(PAYMENTS).queryParam("orderId", providerOrderId).build())
                    .retrieve()
                    .body(PgTransactionResponse[].class);
                if (response == null) {
                    throw new IllegalStateException("PG 목록 응답 본문이 없습니다");
                }
                return Arrays.stream(response).map(PgTransactionResponse::toTransaction).toList();
            } catch (HttpClientErrorException e) {
                throw new PgLookupClientErrorException("PG 조회가 거절됐습니다: " + e.getStatusCode().value(), e);
            }
        });
    }

    /**
     * 재시도(바깥) → 회로(안쪽) → HTTP 순서로 감싼다. 시도 하나하나가 회로 표본이 되고, 회로가 열리면 재시도하지 않는다.
     */
    private <T> T lookup(Supplier<T> call) {
        Supplier<T> attempt = () -> {
            try {
                return call.get();
            } catch (HttpServerErrorException e) {
                throw new PgLookupRetryableException("PG 조회 응답이 5xx입니다: " + e.getStatusCode().value(), e);
            } catch (ResourceAccessException e) {
                throw new PgLookupRetryableException("PG 조회 응답을 받지 못했습니다", e);
            } catch (RestClientException | IllegalStateException e) {
                throw new PgLookupRetryableException("PG 조회 응답을 해석하지 못했습니다", e);
            }
        };
        Supplier<T> decorated = Retry.decorateSupplier(lookupRetry, CircuitBreaker.decorateSupplier(lookupCircuit, attempt));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("PG 조회 회로가 열려 조회하지 않았습니다");
            throw new PgLookupUnavailableException("PG 조회 회로가 열려 있습니다", e);
        } catch (PgLookupRetryableException | PgLookupClientErrorException e) {
            log.warn("PG 조회 실패: {}", e.getMessage());
            throw new PgLookupUnavailableException(e.getMessage(), e);
        }
    }

    /**
     * 연결 자체가 안 됐으면(거부·연결 timeout) 요청 바이트가 나가지 않았다.
     */
    private static boolean isNotSent(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
