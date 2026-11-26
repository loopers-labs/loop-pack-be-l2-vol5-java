package com.loopers.infrastructure.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.fixture.pg.JournalEntry;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponseFailure;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.pgsimulator.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 RestClient·회로·테스트 PG fixture 로 PG 경계 진단 로그를 확인한다(checkout-incident §4.2).
 * 회로 표본 집계는 기존 회로 테스트가 맡는다.
 */
@DisplayName("PG 경계 진단 로그 검증")
class PgHttpGatewayDiagnosticsTest extends PgAdapterTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CARD_NO = "0000-0000-0000-0000";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(PgHttpGateway.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @DisplayName("호출마다 callId 로 START/RESULT 를 짝짓고 허용 필드만 남긴다.")
    @Test
    void logsStartAndResultPerCall() {
        withGateway(gateway -> {
            PaymentObservation created = gateway.create(request("LP-ORD-0000000600", 7000));
            String key = ((PaymentObservation.TransactionObserved) created).transactionKey();
            gateway.findByTransactionKey(key);
            gateway.findByOrderId("LP-ORD-0000000600");
        });

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(events).extracting(e -> e.get("event").textValue() + "/" + e.get("operation").textValue())
                .containsExactly("PAYMENT_PG_START/CREATE", "PAYMENT_PG_RESULT/CREATE",
                    "PAYMENT_PG_START/LOOKUP_KEY", "PAYMENT_PG_RESULT/LOOKUP_KEY",
                    "PAYMENT_PG_START/LOOKUP_ORDER", "PAYMENT_PG_RESULT/LOOKUP_ORDER"),
            () -> assertThat(events.get(0).get("callId")).isEqualTo(events.get(1).get("callId")),
            () -> assertThat(events.get(2).get("callId")).isEqualTo(events.get(3).get("callId")),
            () -> assertThat(events.get(4).get("callId")).isEqualTo(events.get(5).get("callId")),
            () -> assertThat(List.of(events.get(0).get("callId"), events.get(2).get("callId"), events.get(4).get("callId")))
                .doesNotHaveDuplicates(),
            () -> assertThat(fieldNames(events.get(0))).containsExactly("event", "at", "callId", "operation", "providerOrderId"),
            () -> assertThat(events.get(1).get("classification").textValue()).isEqualTo("TransactionObserved"),
            () -> assertThat(events.get(1).get("source").textValue()).isEqualTo("CREATE"),
            () -> assertThat(events.get(1).get("reason").textValue()).isEqualTo("RESULT_DECODED"),
            () -> assertThat(events.get(1).get("providerOrderId").textValue()).isEqualTo("LP-ORD-0000000600"),
            () -> assertThat(events.get(1).get("amount").longValue()).isEqualTo(7000),
            () -> assertThat(events.get(1).get("status").textValue()).isEqualTo("PENDING"),
            () -> assertThat(events.get(2).get("transactionKey")).isEqualTo(events.get(1).get("transactionKey")),
            () -> assertThat(events.get(5).get("source").textValue()).isEqualTo("QUERY"),
            () -> assertThat(rawMessages()).noneMatch(line -> line.contains(CARD_NO) || line.contains("callback")
                || line.contains("cardType") || line.contains("X-USER-ID"))
        );
    }

    @DisplayName("SUCCESS 저장 후 응답 유실은 통신 실패로 기록한다.")
    @Test
    void successThenLostResponseIsTransportUnavailable() {
        pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000601", ResponsePlan.successThenCloseEarly());

        PaymentObservation[] observed = new PaymentObservation[1];
        withGateway(gateway -> observed[0] = gateway.create(request("LP-ORD-0000000601", 7000)));
        awaitHandlersFinished();

        JournalEntry entry = pg.state().journal().get(0);
        JsonNode result = events().get(1);
        assertAll(
            () -> assertThat(observed[0]).isEqualTo(new PaymentObservation.Unavailable(ObservationSource.CREATE)),
            () -> assertThat(result.get("event").textValue()).isEqualTo("PAYMENT_PG_RESULT"),
            () -> assertThat(result.get("classification").textValue()).isEqualTo("Unavailable"),
            () -> assertThat(result.get("reason").textValue()).isEqualTo("TRANSPORT_UNAVAILABLE"),
            () -> assertThat(entry.failure()).isEqualTo(ResponseFailure.CLOSED_EARLY),
            () -> assertThat(pg.state().transactions()).singleElement()
                .satisfies(transaction -> assertThat(transaction.status()).isEqualTo(PaymentStatus.SUCCESS))
        );
    }

    @DisplayName("계약 외 응답은 해석 불가로 기록한다.")
    @Test
    void decoderFailuresAreNotTransport() {
        pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000602", ResponsePlan.raw(500, "{}", null));

        PaymentObservation[] observed = new PaymentObservation[3];
        withGateway(gateway -> {
            observed[0] = gateway.create(request("LP-ORD-0000000602", 7000));
            String key = pg.state().transactions().get(0).transactionKey();
            pg.enqueuePlan(PgOperation.LOOKUP_TRANSACTION, key, ResponsePlan.raw(500, "{}", null));
            pg.enqueuePlan(PgOperation.LOOKUP_ORDER, "LP-ORD-0000000602", ResponsePlan.closeEarly());
            observed[1] = gateway.findByTransactionKey(key);
            observed[2] = gateway.findByOrderId("LP-ORD-0000000602");
        });

        List<JsonNode> results = events().stream().filter(e -> e.get("event").textValue().equals("PAYMENT_PG_RESULT")).toList();
        assertAll(
            () -> assertThat(observed).containsExactly(new PaymentObservation.Unavailable(ObservationSource.CREATE),
                new PaymentObservation.Unavailable(ObservationSource.QUERY),
                new PaymentObservation.Unavailable(ObservationSource.QUERY)),
            () -> assertThat(results).extracting(e -> e.get("reason").textValue())
                .containsExactly("CREATE_RESULT_UNAVAILABLE", "LOOKUP_RESULT_UNAVAILABLE", "TRANSPORT_UNAVAILABLE")
        );
    }

    @DisplayName("충돌 근거만 기록하고 잘못된 식별자는 INVALID 로 남긴다.")
    @Test
    void conflictAndInvalidIdentifiers() {
        String body = "{\"transactionKey\":\"tx-raw\",\"orderId\":\"LP-ORD-0000000603\",\"amount\":1,\"status\":\"PENDING\"}";
        pg.enqueuePlan(PgOperation.CREATE, "LP-ORD-0000000603", ResponsePlan.raw(202, body, null));

        withGateway(gateway -> {
            gateway.create(request("LP-ORD-0000000603", 7000));
            gateway.findByOrderId("bad\"\n<order>");
        });

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(events.get(1).get("classification").textValue()).isEqualTo("Conflict"),
            () -> assertThat(events.get(1).get("conflictCode").textValue()).isEqualTo("AMOUNT_MISMATCH"),
            () -> assertThat(events.get(1).get("observedAmount").longValue()).isEqualTo(1),
            () -> assertThat(events.get(1).get("observedTransactionKey").textValue()).isEqualTo("tx-raw"),
            () -> assertThat(events.get(2).get("providerOrderId").textValue()).isEqualTo("INVALID"),
            () -> assertThat(rawMessages()).noneMatch(line -> line.contains("<order>"))
        );
    }

    @DisplayName("미분류 예외는 ERROR 기록 후 그대로 던진다.")
    @Test
    void unclassifiedExceptionPropagatesWithErrorLog() {
        IllegalStateException failure = new IllegalStateException("secret-host password=hunter2");

        withHookedGateway(new PgHttpGateway.CreateCallHook() {
            @Override
            public void afterEntered() {
                throw failure;
            }
        }, gateway -> assertThatThrownBy(() -> gateway.create(request("LP-ORD-0000000604", 7000))).isSameAs(failure));

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(events).extracting(e -> e.get("event").textValue())
                .containsExactly("PAYMENT_PG_START", "PAYMENT_PG_ERROR"),
            () -> assertThat(events.get(1).get("callId")).isEqualTo(events.get(0).get("callId")),
            () -> assertThat(events.get(1).get("exception").textValue()).isEqualTo("java.lang.IllegalStateException"),
            () -> assertThat(events.get(1).get("reason").textValue()).isEqualTo("UNCLASSIFIED_PROPAGATED"),
            () -> assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull()),
            () -> assertThat(rawMessages()).noneMatch(line -> line.contains("hunter2") || line.contains("secret-host")),
            () -> assertThat(pg.state().counts().createReceived()).as("본문 진입 직후 실패라 POST 없음").isZero()
        );
    }

    private List<JsonNode> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).map(PgHttpGatewayDiagnosticsTest::json).toList();
    }

    private List<String> rawMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** "EVENT {json}" 에서 JSON 부분을 읽는다. */
    private static JsonNode json(String line) {
        try {
            return MAPPER.readTree(line.substring(line.indexOf(' ') + 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void withGateway(GatewayTest test) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class, PgHttpGateway.class)
            .withPropertyValues("commerce.pg.base-url=" + pg.baseUrl())
            .run(context -> {
                assertThat(context).hasNotFailed();
                test.run(context.getBean(PgHttpGateway.class));
            });
    }

    private void withHookedGateway(PgHttpGateway.CreateCallHook hook, GatewayTest test) {
        PgHttpConfigTest.RUNNER.withUserConfiguration(PgResponseDecoder.class)
            .withPropertyValues("commerce.pg.base-url=" + pg.baseUrl())
            .run(context -> {
                assertThat(context).hasNotFailed();
                test.run(new PgHttpGateway(context.getBean("pgRestClient", RestClient.class),
                    context.getBean(PgResponseDecoder.class), context.getBean(PgHttpProperties.class),
                    context.getBean(CircuitBreakerRegistry.class), hook));
            });
    }

    private static PaymentRequest request(String providerOrderId, long amount) {
        return new PaymentRequest(providerOrderId, amount, "TEST", CARD_NO);
    }

    @FunctionalInterface
    private interface GatewayTest {
        void run(PgHttpGateway gateway) throws Exception;
    }
}
