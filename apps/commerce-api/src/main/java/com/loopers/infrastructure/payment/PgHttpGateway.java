package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.CreateAdmission;
import com.loopers.domain.payment.LocalNotDispatchedReason;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * PG HTTP adapter(설계 2.8). 요청마다 POST·GET 을 한 번만 보내고 재시도·redirect 추적을 하지 않는다.
 * 본문을 끝까지 받아 연결을 반환한 뒤 decoder 로 해석한다. 통신 실패와 해석 불가는 확인 불가 관찰로 바꾸고,
 * 식별하지 못한 예외와 식별한 프로그램 오류는 전파한다.
 *
 * <p>생성·조회는 Boot registry 의 pgCreate·pgLookup 회로가 각각 HTTP·해석까지 한 번 감싼다. 회로가 결과·예외를 기록한 뒤
 * 회로 밖에서만 관찰로 바꾸므로 실패 집계를 삼키지 않는다. 직접 대상 위반 결과는 실패로 기록돼도 충돌 관찰을 그대로 돌려준다.
 * 수동 acquirePermission·onSuccess·onError 는 쓰지 않는다.
 */
@Component
public class PgHttpGateway implements PaymentGateway {

    static final String PAYMENTS_PATH = "/api/v1/payments";

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final RestClient restClient;
    private final PgResponseDecoder decoder;
    private final PgHttpProperties properties;
    private final CircuitBreaker createCircuit;
    private final CircuitBreaker lookupCircuit;
    private final CreateCallHook createCallHook;

    @Autowired
    public PgHttpGateway(@Qualifier("pgRestClient") RestClient restClient, PgResponseDecoder decoder,
                         PgHttpProperties properties, CircuitBreakerRegistry circuitBreakerRegistry) {
        this(restClient, decoder, properties, circuitBreakerRegistry, CreateCallHook.NONE);
    }

    PgHttpGateway(RestClient restClient, PgResponseDecoder decoder, PgHttpProperties properties,
                  CircuitBreakerRegistry circuitBreakerRegistry, CreateCallHook createCallHook) {
        this.restClient = restClient;
        this.decoder = decoder;
        this.properties = properties;
        // YAML 에 없는 이름으로 기본 설정 회로가 새로 만들어지지 않도록 기존 인스턴스만 찾는다.
        this.createCircuit = existingCircuit(circuitBreakerRegistry, PgCircuitConfig.PG_CREATE);
        this.lookupCircuit = existingCircuit(circuitBreakerRegistry, PgCircuitConfig.PG_LOOKUP);
        this.createCallHook = createCallHook;
    }

    /** 권한을 얻으면 즉시 돌려줘 HALF_OPEN 탐색 슬롯을 보유하지 않는다. 성공·실패·느림 표본은 남지 않는다. */
    @Override
    public CreateAdmission checkCreateAdmission() {
        if (!createCircuit.tryAcquirePermission()) {
            return CreateAdmission.BLOCKED;
        }
        createCircuit.releasePermission();
        return CreateAdmission.READY;
    }

    /**
     * 설계 2.9 create 의사코드. 진입 표시는 이 호출 안에서만 쓰며 decorator 본문의 첫 동작에서 바꾼다.
     * decorator 실행 호출 전(요청 준비)의 예외는 잡지 않으므로 pgCreate 표본 없이 전파된다.
     */
    @Override
    public PaymentObservation create(PaymentRequest request) {
        CreateCall call = new CreateCall();
        Map<String, Object> body = createBody(request);
        URI uri = uri(PAYMENTS_PATH, null, null);
        createCallHook.beforeDecorator();
        try {
            return createCircuit.executeSupplier(() -> {
                call.markEntered();
                try {
                    createCallHook.afterEntered();
                    return decoder.decodeCreate(post(uri, body, call), request);
                } catch (CallNotPermittedException unexpected) {
                    // 본문에는 다른 회로 호출이 없으므로 의도한 진입 거절이 아니다. 감싸서 ignore 로 표본에서 뺀다.
                    throw new PgAdapterProgrammingException("생성 본문 안의 예상 밖 회로 거절", unexpected);
                }
            }).observation();
        } catch (CallNotPermittedException e) {
            if (!call.entered() && createCircuit.getName().equals(e.getCausingCircuitBreakerName())) {
                return new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED);
            }
            throw e;
        } catch (PgPoolAcquireException e) {
            // 표본에서는 이미 제외됐다. 이 호출의 단일 POST 최초 lease 실패일 때만 미발송을 확정한다.
            if (call.postExchanges() == 1) {
                return new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.POOL_ACQUIRE_TIMEOUT);
            }
            return new PaymentObservation.Unavailable(ObservationSource.CREATE);
        } catch (PgCreateUnavailableException e) {
            return new PaymentObservation.Unavailable(ObservationSource.CREATE);
        }
    }

    @Override
    public PaymentObservation findByTransactionKey(String transactionKey) {
        URI uri = uri(PAYMENTS_PATH + "/{transactionKey}", null, transactionKey);
        return lookup(() -> decoder.decodeLookupByKey(get(uri), transactionKey));
    }

    @Override
    public PaymentObservation findByOrderId(String providerOrderId) {
        URI uri = uri(PAYMENTS_PATH, "orderId", providerOrderId);
        return lookup(() -> decoder.decodeLookupByOrder(get(uri), providerOrderId));
    }

    /** 회로 거절·풀 대기 제외·승인된 조회 실패만 확인 불가로 바꾼다. 프로그램 오류와 식별하지 못한 예외는 전파한다. */
    private PaymentObservation lookup(Supplier<PgLookupResult> getAndDecode) {
        try {
            return lookupCircuit.executeSupplier(getAndDecode).observation();
        } catch (CallNotPermittedException | PgPoolAcquireException | PgLookupUnavailableException e) {
            return new PaymentObservation.Unavailable(ObservationSource.QUERY);
        }
    }

    private Map<String, Object> createBody(PaymentRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", request.providerOrderId());
        body.put("cardType", request.cardType());
        body.put("cardNo", request.cardNo());
        body.put("amount", request.amount());
        body.put("callbackUrl", properties.callbackUrl().toString());
        return body;
    }

    /**
     * 생성 POST 의 exchange 는 호출마다 한 번이다. 풀 대기 실패의 미발송 판정(2.8 POOL_ACQUIRE_TIMEOUT)은 이 단일 exchange 와
     * PgHttpConfig 의 client 보장 조건(자동 retry·redirect 비활성, 재실행 interceptor 없음)에 기댄다.
     */
    private PgHttpResponse post(URI uri, Map<String, Object> body, CreateCall call) {
        call.countPostExchange();
        try {
            return restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((httpRequest, httpResponse) -> new PgHttpResponse(
                    httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
        } catch (ResourceAccessException e) {
            throw classify(e, PgCreateUnavailableException::new);
        }
    }

    private PgHttpResponse get(URI uri) {
        try {
            return restClient.get()
                .uri(uri)
                .exchange((httpRequest, httpResponse) -> new PgHttpResponse(
                    httpResponse.getStatusCode().value(), readBounded(httpResponse.getBody())));
        } catch (ResourceAccessException e) {
            throw classify(e, PgLookupUnavailableException::new);
        }
    }

    /**
     * 경로 변수와 query 값을 예약 문자까지 엄격하게 인코딩한다. 그래야 '+'·'&' 같은 문자를 포함한 주문 ID·거래 키도
     * 생성 때와 같은 문자열로 PG 에 전달된다.
     */
    private URI uri(String pathTemplate, String queryName, String variable) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.baseUrl()).path(pathTemplate);
        if (queryName != null) {
            builder.queryParam(queryName, "{value}");
        }
        return variable == null
            ? builder.build().toUri()
            : builder.encode().buildAndExpand(variable).toUri();
    }

    /**
     * RestClient 는 연결·전송·수신 중 I/O 오류를 ResourceAccessException 으로 감싼다. 원인 사슬에 정확한 타입의
     * ConnectionRequestTimeoutException 이 있으면 우리 풀 대기 실패이고, 나머지는 PG 통신 실패다(2.8). 상위 타입으로 판정하지 않는다.
     */
    private static RuntimeException classify(ResourceAccessException e,
                                             BiFunction<String, Throwable, ? extends RuntimeException> unavailable) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getClass() == ConnectionRequestTimeoutException.class) {
                return new PgPoolAcquireException(e);
            }
        }
        return unavailable.apply("PG 통신 실패", e);
    }

    /** 계약상 작은 JSON 만 오므로 상한을 넘는 본문은 해석 불가로 다룬다. */
    private static byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            return new byte[0];
        }
        return bytes;
    }

    private static CircuitBreaker existingCircuit(CircuitBreakerRegistry registry, String name) {
        return registry.find(name).orElseThrow(() -> new IllegalStateException(name + " 회로 설정 없음"));
    }

    /** 생성 호출 하나의 로컬 상태. Gateway 필드·다른 요청과 공유하지 않는다. */
    private static final class CreateCall {

        private boolean entered;
        private int postExchanges;

        void markEntered() {
            entered = true;
        }

        boolean entered() {
            return entered;
        }

        void countPostExchange() {
            postExchanges++;
        }

        int postExchanges() {
            return postExchanges;
        }
    }

    /**
     * 4.5 미발송 판정 검증용 seam. decorator 실행 호출 직전과 본문 진입 직후(POST 전)에 예외를 주입한다.
     * 운영 bean 은 아무것도 하지 않는 NONE 을 쓴다.
     */
    interface CreateCallHook {

        CreateCallHook NONE = new CreateCallHook() {
        };

        default void beforeDecorator() {
        }

        default void afterEntered() {
        }
    }
}
