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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PG adapter(설계 2.8). HTTP 송수신은 {@link PgHttpTransport} 에 맡기고, 여기서는 두 가지만 한다.
 * <ol>
 *   <li>생성은 pgCreate, 조회는 pgLookup 회로로 "HTTP 호출 + 응답 해석"을 한 번 감싼다.</li>
 *   <li>회로가 결과·예외를 기록한 뒤, 회로 밖에서 그 결과를 업무 관찰(성공·실패·미발송·확인 불가)로 바꾼다.</li>
 * </ol>
 * 관찰로 바꾸는 일을 회로 밖에서 하므로 실패 집계를 삼키지 않는다. 수동 acquirePermission·onSuccess·onError 는 쓰지 않는다.
 * 식별하지 못한 예외와 프로그램 오류는 관찰로 바꾸지 않고 전파한다.
 *
 * <p>생성 실패를 "확실히 보내지 않음(LocalNotDispatched)"으로 판정하는 경우는 둘뿐이다. 이때만 재고를 바로 반환할 수 있다.
 * <pre>
 * 경우                        조건                                                    결과
 * 회로가 호출을 거절함         이 호출이 회로 본문에 들어가지 않았고, 거절한 회로가 pgCreate  CIRCUIT_REJECTED
 * 풀에서 연결을 얻지 못함       이 호출의 POST exchange 가 정확히 1번(최초 연결 획득에서 실패)  POOL_ACQUIRE_TIMEOUT
 * 그 밖의 풀 대기 실패·통신 실패  -                                                        확인 불가(UNKNOWN 유지)
 * </pre>
 */
@Component
public class PgHttpGateway implements PaymentGateway {

    private final PgHttpTransport transport;
    private final PgResponseDecoder decoder;
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
        this.transport = new PgHttpTransport(restClient, properties);
        this.decoder = decoder;
        // YAML 에 없는 이름으로 기본 설정 회로가 새로 만들어지지 않도록 기존 인스턴스만 찾는다.
        this.createCircuit = existingCircuit(circuitBreakerRegistry, PgCircuitConfig.PG_CREATE);
        this.lookupCircuit = existingCircuit(circuitBreakerRegistry, PgCircuitConfig.PG_LOOKUP);
        this.createCallHook = createCallHook;
    }

    /**
     * 재고를 확보하기 전에 생성 회로가 지금 막혀 있는지만 본다. 권한을 얻으면 바로 돌려줘 HALF_OPEN 탐색 슬롯을 붙잡지 않으며,
     * 성공·실패·느림 표본도 남기지 않는다. READY 는 이후 실제 POST 권한을 보장하지 않는다.
     */
    @Override
    public CreateAdmission checkCreateAdmission() {
        if (!createCircuit.tryAcquirePermission()) {
            return CreateAdmission.BLOCKED;
        }
        createCircuit.releasePermission();
        return CreateAdmission.READY;
    }

    /**
     * 요청 준비(본문·URI) → 회로 안에서 보내고 해석 → 회로 밖에서 예외를 관찰로 변환. 요청 준비 중 예외는 회로 표본 없이 전파된다.
     */
    @Override
    public PaymentObservation create(PaymentRequest request) {
        CreateCall call = new CreateCall();
        Map<String, Object> body = transport.createBody(request);
        URI uri = transport.createUri();
        createCallHook.beforeDecorator();
        try {
            return createCircuit.executeSupplier(() -> sendAndDecode(request, uri, body, call)).observation();
        } catch (CallNotPermittedException e) {
            return circuitRejected(e, call);
        } catch (PgPoolAcquireException e) {
            // 회로 표본에서는 이미 제외됐다. 최초 연결 획득에서 실패한 경우만 보내지 않은 것이 확실하다.
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
        URI uri = transport.transactionUri(transactionKey);
        return lookup(() -> decoder.decodeLookupByKey(transport.get(uri), transactionKey));
    }

    @Override
    public PaymentObservation findByOrderId(String providerOrderId) {
        URI uri = transport.orderLookupUri(providerOrderId);
        return lookup(() -> decoder.decodeLookupByOrder(transport.get(uri), providerOrderId));
    }

    /** 회로 본문. 첫 동작으로 "본문에 들어왔음"을 표시해야 회로 거절과 본문 안 예외를 구분할 수 있다. */
    private PgCreateResult sendAndDecode(PaymentRequest request, URI uri, Map<String, Object> body, CreateCall call) {
        call.markEntered();
        try {
            createCallHook.afterEntered();
            call.countPostExchange();
            return decoder.decodeCreate(transport.post(uri, body), request);
        } catch (CallNotPermittedException unexpected) {
            // 본문에는 다른 회로 호출이 없으므로 의도한 진입 거절이 아니다. 감싸서 ignore 로 표본에서 뺀다.
            throw new PgAdapterProgrammingException("생성 본문 안의 예상 밖 회로 거절", unexpected);
        }
    }

    /** 이 호출이 본문에 들어가기 전에 pgCreate 가 거절한 경우만 미발송이다. 그 밖의 거절은 예상 밖이라 전파한다. */
    private PaymentObservation circuitRejected(CallNotPermittedException e, CreateCall call) {
        if (!call.entered() && createCircuit.getName().equals(e.getCausingCircuitBreakerName())) {
            return new PaymentObservation.LocalNotDispatched(LocalNotDispatchedReason.CIRCUIT_REJECTED);
        }
        throw e;
    }

    /** 회로 거절·풀 대기 실패·조회 통신 실패만 확인 불가로 바꾼다. 프로그램 오류와 식별하지 못한 예외는 전파한다. */
    private PaymentObservation lookup(Supplier<PgLookupResult> getAndDecode) {
        try {
            return lookupCircuit.executeSupplier(getAndDecode).observation();
        } catch (CallNotPermittedException | PgPoolAcquireException | PgLookupUnavailableException e) {
            return new PaymentObservation.Unavailable(ObservationSource.QUERY);
        }
    }

    private static CircuitBreaker existingCircuit(CircuitBreakerRegistry registry, String name) {
        return registry.find(name).orElseThrow(() -> new IllegalStateException(name + " 회로 설정 없음"));
    }

    /** 생성 호출 하나의 로컬 기록. Gateway 필드·다른 요청과 공유하지 않는다. */
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
     * 미발송 판정 검증용 seam(설계 4.5). 회로 실행 직전과 본문 진입 직후(POST 전)에 예외를 주입한다.
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
