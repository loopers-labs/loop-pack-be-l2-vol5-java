package com.loopers.application.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.ObservationSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 DB 로 만들기 어려운 분기(synchronization 없음·종료 상태 불명·등록 실패)를 제어해 표현만 확인한다.
 * 실제 DB 종료 불명 장애를 재현한 것이 아니다.
 */
@DisplayName("결제 TX 진단 단위 검증")
class PaymentTxDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @DisplayName("TX 밖이면 가짜 종료 없이 NO_ACTIVE_SYNCHRONIZATION 을 남긴다.")
    @Test
    void noTransaction() {
        String id = new PaymentTxDiagnostics().observe(PaymentTxDiagnostics.Operation.APPLY_OBSERVATION, 1L, 2L,
            ObservationSource.QUERY);

        List<JsonNode> events = events();
        assertAll(
            () -> assertThat(events).extracting(e -> e.get("event").textValue())
                .containsExactly("TX_METHOD_ENTER", "TX_OBSERVATION_UNAVAILABLE"),
            () -> assertThat(events.get(1).get("reason").textValue()).isEqualTo("NO_ACTIVE_SYNCHRONIZATION"),
            () -> assertThat(events.get(1).get("observationId").textValue()).isEqualTo(id)
        );
    }

    @DisplayName("종료 상태를 COMMITTED·ROLLED_BACK·UNKNOWN 으로 그대로 옮긴다.")
    @Test
    void mapsCompletionStatus() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PaymentTxDiagnostics diagnostics = new PaymentTxDiagnostics();
        for (int i = 0; i < 3; i++) {
            diagnostics.observe(PaymentTxDiagnostics.Operation.BEGIN_CONFIRMATION, 1L, null, ObservationSource.CREATE);
        }
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();

        registered.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        registered.get(1).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        registered.get(2).afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(events()).filteredOn(e -> e.get("event").textValue().equals("TX_COMPLETION"))
            .extracting(e -> e.get("outcome").textValue())
            .containsExactly("COMMITTED", "ROLLED_BACK", "UNKNOWN");
    }

    @DisplayName("등록이 RuntimeException 이면 업무로 전파하지 않고 OBSERVATION_FAILED 를 남긴다.")
    @Test
    void registrationFailureIsLocal() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PaymentTxDiagnostics diagnostics = new PaymentTxDiagnostics(synchronization -> {
            throw new IllegalStateException("secret-host");
        });

        assertThatCode(() -> diagnostics.observe(PaymentTxDiagnostics.Operation.APPLY_OBSERVATION, 1L, 2L,
            ObservationSource.CREATE)).doesNotThrowAnyException();

        JsonNode unavailable = events().get(1);
        assertAll(
            () -> assertThat(unavailable.get("reason").textValue()).isEqualTo("OBSERVATION_FAILED"),
            () -> assertThat(unavailable.get("exception").textValue()).isEqualTo("java.lang.IllegalStateException"),
            () -> assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("secret-host"))
        );
    }

    @DisplayName("등록 중 Error 는 삼키지 않는다.")
    @Test
    void registrationErrorPropagates() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PaymentTxDiagnostics diagnostics = new PaymentTxDiagnostics(synchronization -> {
            throw new StackOverflowError();
        });

        assertThatThrownBy(() -> diagnostics.observe(PaymentTxDiagnostics.Operation.APPLY_OBSERVATION, 1L, 2L,
            ObservationSource.CREATE)).isInstanceOf(StackOverflowError.class);
    }

    @DisplayName("문맥 기록 중 예외는 같은 observationId 로 OBSERVATION_FAILED 만 남긴다.")
    @Test
    void contextFailureIsLocal() {
        assertThatCode(() -> new PaymentTxDiagnostics().paymentContext("obs-1", null, null)).doesNotThrowAnyException();

        JsonNode unavailable = events().get(0);
        assertAll(
            () -> assertThat(unavailable.get("event").textValue()).isEqualTo("TX_OBSERVATION_UNAVAILABLE"),
            () -> assertThat(unavailable.get("observationId").textValue()).isEqualTo("obs-1"),
            () -> assertThat(unavailable.get("reason").textValue()).isEqualTo("OBSERVATION_FAILED")
        );
    }

    private List<JsonNode> events() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).map(line -> {
            try {
                return MAPPER.readTree(line.substring(line.indexOf(' ') + 1));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
    }
}
