package com.loopers.application.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 MySQL·Spring TX 프록시로 결제 TX 진단의 commit/rollback 기록을 확인한다(checkout-incident §4.3·4.4).
 * 테스트 부모 TX 없이 프록시로 부르고, 업무 결과는 새 연결로 읽는다.
 */
@SpringBootTest
@Import(PaymentTxTestConfig.class)
@DisplayName("결제 TX 진단 통합 검증")
class PaymentTxDiagnosticsIntegrationTest {

    private static final Long USER = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private PaymentTransactionService service;
    @Autowired
    private PaymentTxFixture fixture;
    @Autowired
    private ControllableProbe probe;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(PaymentTxDiagnostics.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        probe.clear();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("결제 시작은 commit 뒤에만 COMMITTED 를 남기고 저장 식별자를 연결한다.")
    @Test
    void beginCommits() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 1L, product);

        PaymentCheckToken token = begin(orderId, PaymentTxFixture.KEY_A).token();

        List<JsonNode> events = events();
        String id = events.get(0).get("observationId").textValue();
        JsonNode context = events.get(1);
        assertAll(
            () -> assertThat(names(events)).containsExactly("TX_METHOD_ENTER", "PAYMENT_CONTEXT", "TX_COMPLETION"),
            () -> assertThat(events).allSatisfy(e -> assertThat(e.get("observationId").textValue()).isEqualTo(id)),
            () -> assertThat(events.get(0).get("operation").textValue()).isEqualTo("BEGIN_CONFIRMATION"),
            () -> assertThat(events.get(0).get("paymentId").isNull()).as("insert 전 paymentId 없음").isTrue(),
            () -> assertThat(context.get("paymentId").longValue()).isEqualTo(token.paymentId()),
            () -> assertThat(context.get("attemptKey").textValue()).isEqualTo(PaymentTxFixture.KEY_A),
            () -> assertThat(context.get("providerOrderId").textValue()).isEqualTo(PaymentModel.providerOrderIdOf(orderId)),
            () -> assertThat(context.get("amount").longValue()).isEqualTo(1_000L),
            () -> assertThat(context.get("paymentStatus").textValue()).isEqualTo("UNKNOWN"),
            () -> assertThat(events.get(2).get("outcome").textValue()).isEqualTo("COMMITTED"),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "UNKNOWN"),
            () -> assertThat(rawMessages()).noneMatch(line -> line.contains(PaymentTransactionService.SYNTHETIC_CARD_NO))
        );
    }

    @DisplayName("응답 유실(Unavailable) 반영은 UNAVAILABLE 판정이 commit 되고 주문은 확정되지 않는다.")
    @Test
    void unavailableCommitsWithoutConfirming() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 1L, product);
        PaymentCheckToken token = begin(orderId, PaymentTxFixture.KEY_A).token();
        appender.list.clear();

        service.applyObservation(token, new PaymentObservation.Unavailable(ObservationSource.CREATE));

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(names(events)).containsExactly("TX_METHOD_ENTER", "PAYMENT_CONTEXT", "PAYMENT_APPLY_INPUT",
                "PAYMENT_APPLY_DECISION", "TX_COMPLETION"),
            () -> assertThat(events.get(0).get("source").textValue()).isEqualTo("CREATE"),
            () -> assertThat(events.get(2).get("classification").textValue()).isEqualTo("Unavailable"),
            () -> assertThat(events.get(3).get("effect").textValue()).isEqualTo("UNAVAILABLE"),
            () -> assertThat(events.get(4).get("outcome").textValue()).isEqualTo("COMMITTED"),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "UNKNOWN"),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING"),
            () -> assertThat(fixture.outboxCount(orderId)).isZero()
        );
    }

    @DisplayName("commit 직전 실패는 CONFIRM 판정 기록이 있어도 ROLLED_BACK 이고 DB 는 직전 상태다.")
    @Test
    void probeFailureRollsBack() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 1L, product);
        PaymentCheckToken token = begin(orderId, PaymentTxFixture.KEY_A).token();
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            throw new IllegalStateException("commit 직전 실패 주입");
        });
        appender.list.clear();

        assertThatThrownBy(() -> service.applyObservation(token, success(orderId, "tx-R")))
            .isInstanceOf(IllegalStateException.class);

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(events.get(3).get("effect").textValue()).isEqualTo("CONFIRM"),
            () -> assertThat(events.get(4).get("event").textValue()).isEqualTo("TX_COMPLETION"),
            () -> assertThat(events.get(4).get("outcome").textValue()).isEqualTo("ROLLED_BACK"),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_PENDING"),
            () -> assertThat(fixture.outboxCount(orderId)).isZero()
        );
    }

    @DisplayName("거래 키 충돌은 반영 TX 의 ROLLED_BACK 과 다른 observationId 의 충돌 기록 COMMITTED 로 나뉜다.")
    @Test
    void constraintConflictUsesSeparateTransaction() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long first = fixture.draftOrder(USER, 1L, product);
        Long second = fixture.draftOrder(USER, 1L, product);
        PaymentCheckToken firstToken = begin(first, PaymentTxFixture.KEY_A).token();
        PaymentCheckToken secondToken = begin(second, PaymentTxFixture.KEY_B).token();
        service.applyObservation(firstToken, success(first, "tx-SHARED"));
        PaymentObservation.TransactionObserved reused = new PaymentObservation.TransactionObserved(ObservationSource.CREATE,
            "tx-SHARED", PaymentModel.providerOrderIdOf(second), 1_000L, PgTransactionStatus.PENDING);
        appender.list.clear();

        Throwable violation = catchThrowable(() -> service.applyObservation(secondToken, reused));
        service.recordConstraintConflict(secondToken, reused);

        List<JsonNode> completions = events().stream().filter(e -> e.get("event").textValue().equals("TX_COMPLETION")).toList();
        assertAll(
            () -> assertThat(violation).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(completions).extracting(e -> e.get("operation").textValue() + "/" + e.get("outcome").textValue())
                .containsExactly("APPLY_OBSERVATION/ROLLED_BACK", "RECORD_CONSTRAINT_CONFLICT/COMMITTED"),
            () -> assertThat(completions.get(0).get("observationId")).isNotEqualTo(completions.get(1).get("observationId")),
            () -> assertThat(fixture.order(second)).as("충돌 기록 commit 은 확정이 아님").containsEntry("status", "PAYMENT_PENDING")
        );
    }

    @DisplayName("조회 준비는 선택한 조회 종류·대상을 기록하고 상태를 바꾸지 않는다.")
    @Test
    void prepareVerificationRecordsTarget() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 1L, product);
        begin(orderId, PaymentTxFixture.KEY_A);
        appender.list.clear();

        VerificationTarget target = service.prepareVerification(orderId, USER, null);

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(names(events)).containsExactly("TX_METHOD_ENTER", "PAYMENT_CONTEXT", "TX_COMPLETION"),
            () -> assertThat(events.get(0).get("source").textValue()).isEqualTo("QUERY"),
            () -> assertThat(events.get(1).get("lookupKind").textValue()).isEqualTo("PROVIDER_ORDER_ID"),
            () -> assertThat(events.get(1).get("lookupTarget").textValue()).isEqualTo(target.target()),
            () -> assertThat(events.get(2).get("outcome").textValue()).isEqualTo("COMMITTED"),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "UNKNOWN")
        );
    }

    private BeginConfirmationResult begin(Long orderId, String key) {
        return service.beginConfirmation(USER, orderId, PaymentTxFixture.command(key));
    }

    private static PaymentObservation.TransactionObserved success(Long orderId, String key) {
        return new PaymentObservation.TransactionObserved(ObservationSource.CREATE, key,
            PaymentModel.providerOrderIdOf(orderId), 1_000L, PgTransactionStatus.SUCCESS);
    }

    private List<JsonNode> events() {
        return rawMessages().stream().map(PaymentTxDiagnosticsIntegrationTest::json).toList();
    }

    private List<String> rawMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<String> names(List<JsonNode> events) {
        return events.stream().map(e -> e.get("event").textValue()).toList();
    }

    private static JsonNode json(String line) {
        try {
            return MAPPER.readTree(line.substring(line.indexOf(' ') + 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
