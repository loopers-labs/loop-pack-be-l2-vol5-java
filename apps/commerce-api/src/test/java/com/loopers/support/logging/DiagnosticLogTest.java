package com.loopers.support.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("진단 로그 형식·실패 격리 검증")
class DiagnosticLogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DisplayName("필드를 순서대로 JSON 이스케이프해 남긴다.")
    @Test
    void writesEscapedJson() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("diagnostic-log-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("callId", "c-1");
        fields.put("note", "a\"b\nc");
        fields.put("amount", 4000L);
        try {
            DiagnosticLog.info(logger, "TEST_EVENT", fields);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String line = appender.list.get(0).getFormattedMessage();
        JsonNode json = MAPPER.readTree(line.substring("TEST_EVENT ".length()));
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertAll(
            () -> assertThat(line).startsWith("TEST_EVENT {").doesNotContain("\n"),
            () -> assertThat(names).containsExactly("event", "at", "callId", "note", "amount"),
            () -> assertThat(json.get("note").textValue()).isEqualTo("a\"b\nc"),
            () -> assertThat(json.get("amount").longValue()).isEqualTo(4000)
        );
    }

    @DisplayName("잘못된 식별자는 INVALID 로 바꾼다.")
    @Test
    void masksInvalidIdentifiers() {
        assertAll(
            () -> assertThat(DiagnosticLog.providerOrderId("LP-ORD-0000000042")).isEqualTo("LP-ORD-0000000042"),
            () -> assertThat(DiagnosticLog.providerOrderId("LP-ORD-42")).isEqualTo("INVALID"),
            () -> assertThat(DiagnosticLog.providerOrderId("x\ny")).isEqualTo("INVALID"),
            () -> assertThat(DiagnosticLog.providerOrderId(null)).isNull(),
            () -> assertThat(DiagnosticLog.transactionKey("tx_A-1")).isEqualTo("tx_A-1"),
            () -> assertThat(DiagnosticLog.transactionKey("tx key")).isEqualTo("INVALID"),
            () -> assertThat(DiagnosticLog.transactionKey(null)).isNull()
        );
    }

    @DisplayName("출력 RuntimeException 은 삼키고 Error 는 전파한다.")
    @Test
    void isolatesRuntimeFailuresButNotErrors() {
        org.slf4j.Logger failing = logger(new IllegalStateException("appender down"));
        org.slf4j.Logger erroring = logger(new StackOverflowError());

        assertAll(
            () -> assertThatCode(() -> DiagnosticLog.info(failing, "TEST_EVENT", Map.of("k", "v"))).doesNotThrowAnyException(),
            () -> assertThatThrownBy(() -> DiagnosticLog.info(erroring, "TEST_EVENT", Map.of("k", "v")))
                .isInstanceOf(StackOverflowError.class)
        );
    }

    /** 모든 호출에서 주어진 예외를 던지는 logger 대역. */
    private static org.slf4j.Logger logger(Throwable failure) {
        return (org.slf4j.Logger) Proxy.newProxyInstance(DiagnosticLogTest.class.getClassLoader(),
            new Class<?>[] {org.slf4j.Logger.class}, (proxy, method, args) -> {
                if (method.getName().equals("getName")) {
                    return "failing";
                }
                throw failure;
            });
    }
}
