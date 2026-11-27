package com.loopers.infrastructure.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentLookupResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * "요청은 보냈고 결과를 확인하지 못했다"를 UNKNOWN 으로 수렴시키는 매핑의 결정적 회귀 테스트.
 * 실제 HTTP·부하·짧은 timeout 에 의존하지 않고 예외를 직접 주입한다.
 * <p>
 * 배경: spring-web 6.2.5 의 JdkClientHttpRequest 는 timeout 처리 중 CompletableFuture 를 취소해
 * CancellationException 을 그대로 올릴 수 있다 (spring-framework#33973, 6.2.10 에서 수정).
 * 그 예외가 catch 를 빠져나가면 응답 유실이 UNKNOWN 으로 기록되지 않는 결함이 된다.
 */
class HttpPaymentGatewayUnknownMappingTest {

    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final String TRANSACTION_KEY = "TEST-TX-0042";
    private static final PaymentCreateCommand COMMAND =
        new PaymentCreateCommand(PROVIDER_ORDER_ID, "TEST", "0000-0000-0000-0000", 7000L);
    private static final PaymentGatewayProperties.CircuitBreaker CIRCUIT =
        new PaymentGatewayProperties.CircuitBreaker("COUNT_BASED", 4, 4, 50f, Duration.ofSeconds(10), 2);

    private HttpPaymentGateway gateway;
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        gateway = new HttpPaymentGateway(
            new PaymentGatewayProperties(true, "http://127.0.0.1:1", "http://127.0.0.1:8080/callback", "135135",
                Duration.ofSeconds(1), Duration.ofMillis(500), CIRCUIT),
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("CancellationException 은 UNKNOWN 으로 수렴하고 예외 타입과 cause chain 을 남긴다.")
    void mapsCancellationToUnknown() {
        PaymentCreateResult result = gateway.unknownCreate(PROVIDER_ORDER_ID, new CancellationException("get"));

        assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.UNKNOWN);
        assertThat(result.transactionKey()).isNull();
        assertThat(result.conflictReason()).isNull();
        assertThat(line()).isEqualTo("pg.create outcome=UNKNOWN providerOrderId=" + PROVIDER_ORDER_ID
            + " exception=CancellationException causeChain=[CancellationException] interrupted=false");
    }

    @Test
    @DisplayName("HTTP 요청 팩토리에서 발생한 취소를 create 와 두 조회의 공개 메서드가 처리한다.")
    void mapsCancellationAtHttpBoundary() {
        failHttpRequestsWith(new CancellationException("get"));

        assertThat(gateway.create(COMMAND)).isEqualTo(PaymentCreateResult.unknown());
        PaymentLookupResult unavailable = PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        assertThat(gateway.findAllByOrderId(PROVIDER_ORDER_ID)).isEqualTo(unavailable);
        assertThat(gateway.findByTransactionKey(TRANSACTION_KEY)).isEqualTo(unavailable);
    }

    @Test
    @DisplayName("호출 경계의 일반 내부 실행 오류는 호출자에게 전파된다.")
    void propagatesUnexpectedRuntimeFailure() {
        IllegalStateException internalFailure = new IllegalStateException("internal failure");
        failHttpRequestsWith(internalFailure);

        assertThat(assertThrows(IllegalStateException.class, () -> gateway.create(COMMAND)))
            .isSameAs(internalFailure);
        assertThat(assertThrows(IllegalStateException.class, () -> gateway.findAllByOrderId(PROVIDER_ORDER_ID)))
            .isSameAs(internalFailure);
        assertThat(assertThrows(IllegalStateException.class, () -> gateway.findByTransactionKey(TRANSACTION_KEY)))
            .isSameAs(internalFailure);
    }

    @Test
    @DisplayName("read timeout 과 connect timeout 은 cause chain 으로만 구분해 남기고, 둘을 섞어 단정하지 않는다.")
    void recordsCauseChainWithoutClassifying() {
        gateway.unknownCreate(PROVIDER_ORDER_ID,
            new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out")));
        String readTimeout = line();

        logs.list.clear();
        gateway.unknownCreate(PROVIDER_ORDER_ID,
            new ResourceAccessException("I/O error", new HttpConnectTimeoutException("connect timed out")));
        String connectTimeout = line();

        // HttpConnectTimeoutException 은 HttpTimeoutException 의 하위 타입이다. 이름을 그대로 남겨 둘이 구분되게 한다.
        assertThat(HttpTimeoutException.class).isAssignableFrom(HttpConnectTimeoutException.class);
        assertThat(readTimeout).contains("causeChain=[ResourceAccessException, HttpTimeoutException]");
        assertThat(connectTimeout).contains("causeChain=[ResourceAccessException, HttpConnectTimeoutException]");
        assertThat(readTimeout).doesNotContain("timeoutKind");
    }

    @Test
    @DisplayName("스레드가 인터럽트된 상태는 interrupted=true 로 구분해 남긴다. 그래도 결과는 UNKNOWN 이다.")
    void recordsInterruptedFlag() {
        Thread.currentThread().interrupt();
        try {
            PaymentCreateResult result = gateway.unknownCreate(PROVIDER_ORDER_ID, new CancellationException("get"));

            assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.UNKNOWN);
            assertThat(line()).contains("interrupted=true");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("원인 chain 이 길어도 민감정보를 남기지 않는다. 예외 메시지는 기록하지 않는다.")
    void doesNotLogExceptionMessages() {
        gateway.unknownCreate(PROVIDER_ORDER_ID,
            new ResourceAccessException("카드번호 0000-0000-0000-0000 전송 실패", new IOException("0000-0000-0000-0000")));

        assertThat(line()).doesNotContain("0000-0000-0000-0000");
        assertThat(line()).doesNotContain("전송 실패");
    }

    private void failHttpRequestsWith(RuntimeException failure) {
        RestClient client = (RestClient) ReflectionTestUtils.getField(gateway, "restClient");
        RestClient failingClient = client.mutate().requestFactory((uri, method) -> {
            throw failure;
        }).build();
        ReflectionTestUtils.setField(gateway, "restClient", failingClient);
    }

    private String line() {
        List<String> lines = logs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.startsWith("pg.create"))
            .toList();
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }
}
