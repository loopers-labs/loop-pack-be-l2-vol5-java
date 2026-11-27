package com.loopers.infrastructure.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 10주차 장애 fixture 계약 (설계 3-3). 실제 HTTP adapter 와 진짜 소켓을 거쳐 "거래는 SUCCESS 로 저장됐는데 응답만 없다"를 만든다.
 * 운영 코드에는 장애 주입 스위치가 없고, 제어는 전부 이 fixture 안이다.
 */
class TestPaymentPgServerIncidentTest {

    private static final String USER_ID = "135135";
    private static final String CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";
    private static final String NORMAL_ORDER = "LP-ORD-0000000001";
    private static final String FAULT_ORDER = "LP-ORD-0000000002";
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final long AMOUNT = 5000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final PaymentGatewayProperties.CircuitBreaker CIRCUIT =
        new PaymentGatewayProperties.CircuitBreaker("COUNT_BASED", 10, 10, 50f, Duration.ofSeconds(10), 2);

    private static TestPaymentPgServer server;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpPaymentGateway gateway;
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void startServer() throws IOException {
        server = new TestPaymentPgServer();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void setUp() {
        server.reset();
        server.enableTransactionStore();
        gateway = new HttpPaymentGateway(
            new PaymentGatewayProperties(true, server.baseUrl(), CALLBACK_URL, USER_ID, CONNECT_TIMEOUT, READ_TIMEOUT, CIRCUIT),
            objectMapper,
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
        server.clearResponseDrops();
    }

    @Test
    @DisplayName("장애 대상만 거래를 SUCCESS 로 저장한 뒤 응답을 유실하고, 정상 주문은 영향을 받지 않는다.")
    void losesResponseOnlyForTargetedOrder_afterStoringSuccess() {
        server.dropCreateResponseFor(FAULT_ORDER);

        PaymentCreateResult normal = gateway.create(command(NORMAL_ORDER));
        PaymentCreateResult fault = gateway.create(command(FAULT_ORDER));

        // 정상 주문: 거래 수락(PENDING). 결제 성공이 아니다.
        assertThat(normal.outcome()).isEqualTo(PaymentCreateResult.Outcome.ACCEPTED);
        assertThat(normal.transactionKey()).isNotBlank();
        assertThat(server.transactionOf(NORMAL_ORDER).status()).isEqualTo("PENDING");

        // 장애 주문: 외부에는 SUCCESS 거래가 있는데 호출자는 결과를 모른다.
        assertThat(fault.outcome()).isEqualTo(PaymentCreateResult.Outcome.UNKNOWN);
        assertThat(fault.transactionKey()).isNull();
        assertThat(server.transactionOf(FAULT_ORDER)).isNotNull();
        assertThat(server.transactionOf(FAULT_ORDER).status()).isEqualTo("SUCCESS");
        assertThat(server.transactionOf(FAULT_ORDER).amount()).isEqualTo(AMOUNT);

        // 로그는 업무 결과와 대상 주문을 남긴다. root cause 이름은 환경마다 달라질 수 있어 값으로 고정하지 않는다.
        assertThat(messages()).anyMatch(line ->
            line.startsWith("pg.create outcome=UNKNOWN providerOrderId=" + FAULT_ORDER) && line.contains("causeChain="));
        assertThat(messages()).anyMatch(line ->
            line.startsWith("pg.create outcome=ACCEPTED providerOrderId=" + NORMAL_ORDER));
        assertThat(messages()).noneMatch(line -> line.contains(CARD_NO));
    }

    @Test
    @DisplayName("기존 PG 조회 API 로 저장된 거래를 확인할 수 있고, 장애 해제 뒤에도 같은 거래가 보존된다.")
    void keepsTransactionsAfterDropIsCleared() {
        server.dropCreateResponseFor(FAULT_ORDER);
        gateway.create(command(FAULT_ORDER));
        String transactionKey = server.transactionOf(FAULT_ORDER).transactionKey();

        server.clearResponseDrops();

        assertThat(server.hasResponseDrops()).isFalse();
        PaymentLookupResult lookup = gateway.findAllByOrderId(FAULT_ORDER);
        assertThat(lookup.outcome()).isEqualTo(PaymentLookupResult.Outcome.FOUND);
        assertThat(lookup.transactions()).hasSize(1);
        PaymentTransaction transaction = lookup.transactions().get(0);
        assertThat(transaction.transactionKey()).isEqualTo(transactionKey);
        assertThat(transaction.providerOrderId()).isEqualTo(FAULT_ORDER);
        assertThat(transaction.amount()).isEqualTo(AMOUNT);
        assertThat(transaction.status()).isEqualTo(PaymentTransaction.Status.SUCCESS);
        assertThat(messages()).anyMatch(line ->
            line.equals("pg.lookup outcome=FOUND orderId=" + FAULT_ORDER + " count=1 statuses=[SUCCESS]"));
    }

    @Test
    @DisplayName("같은 주문으로 create 가 두 번 오면 거래는 늘지 않지만 호출 횟수는 2 로 남는다.")
    void countsDuplicateCreateWithoutCreatingSecondTransaction() {
        gateway.create(command(NORMAL_ORDER));
        gateway.create(command(NORMAL_ORDER));

        assertThat(server.createCount(NORMAL_ORDER)).isEqualTo(2);
        assertThat(server.createCount()).isEqualTo(2);
        assertThat(server.storedTransactions()).hasSize(1);
        assertThat(server.transactionOf(NORMAL_ORDER).transactionKey())
            .isEqualTo(server.storedTransactions().get(0).transactionKey());
    }

    @Test
    @DisplayName("응답을 유실한 create 도 호출 횟수에 포함된다. 결과를 못 받았다고 호출 사실이 지워지지 않는다.")
    void countsCreateEvenWhenResponseIsLost() {
        server.dropCreateResponseFor(FAULT_ORDER);

        gateway.create(command(FAULT_ORDER));

        assertThat(server.createCount(FAULT_ORDER)).isEqualTo(1);
        assertThat(server.receivedRequests()).anyMatch(request ->
            "POST".equals(request.method()) && request.body().contains(FAULT_ORDER));
    }

    @Test
    @DisplayName("markSuccess 는 외부 최종 성공 사실만 기록한다. 거래 수와 호출 횟수는 바뀌지 않는다.")
    void marksExternalSuccessWithoutNewTransaction() {
        gateway.create(command(NORMAL_ORDER));

        server.markSuccess(NORMAL_ORDER);

        assertThat(server.transactionOf(NORMAL_ORDER).status()).isEqualTo("SUCCESS");
        assertThat(server.storedTransactions()).hasSize(1);
        assertThat(server.createCount(NORMAL_ORDER)).isEqualTo(1);
    }

    @Test
    @DisplayName("연결 실패도 UNKNOWN 으로 수렴하고 cause chain 을 남긴다. 원인 이름을 고정 값으로 가정하지 않는다.")
    void convergesToUnknownOnConnectionFailure() throws IOException {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpPaymentGateway unreachable = new HttpPaymentGateway(
            new PaymentGatewayProperties(true, "http://127.0.0.1:" + closedPort, CALLBACK_URL, USER_ID,
                CONNECT_TIMEOUT, READ_TIMEOUT, CIRCUIT),
            objectMapper,
            Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));

        PaymentCreateResult result = unreachable.create(command(NORMAL_ORDER));

        assertThat(result.outcome()).isEqualTo(PaymentCreateResult.Outcome.UNKNOWN);
        List<String> lines = messages().stream()
            .filter(line -> line.startsWith("pg.create outcome=UNKNOWN providerOrderId=" + NORMAL_ORDER))
            .toList();
        assertThat(lines).hasSize(1);
        // 업무 결과(UNKNOWN)와 증거(cause chain)만 계약으로 본다. 어떤 예외 이름이 나오는지는 JDK·환경에 따라 달라진다.
        assertThat(lines.get(0)).contains("causeChain=[");
        assertThat(lines.get(0)).contains("interrupted=false");
        assertThat(server.createCount(NORMAL_ORDER)).isZero();
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private PaymentCreateCommand command(String providerOrderId) {
        return new PaymentCreateCommand(providerOrderId, CARD_TYPE, CARD_NO, AMOUNT);
    }
}
