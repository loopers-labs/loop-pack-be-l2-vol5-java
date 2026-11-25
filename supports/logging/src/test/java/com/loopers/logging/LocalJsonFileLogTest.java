package com.loopers.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LocalJsonFileLogTest {

    private static final String CONFIG_LOCATION = "classpath:logback/logback.xml";
    private static final List<String> MDC_KEYS = List.of(
        "orderId", "attemptKey", "providerOrderId", "transactionKey", "traceId", "spanId"
    );

    // appender는 마스킹하지 않는다. 넣은 MDC 키 외에 logstash 기본 필드만 쓰는지(다른 값을 끌어오지 않는지)를 본다.
    private static final List<String> LOGSTASH_FIELDS = List.of(
        "@timestamp", "@version", "message", "logger_name", "thread_name", "level", "level_value"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LogbackLoggingSystem loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        MDC.clear();
        loggingSystem.cleanUp();
        System.clearProperty(LogFile.FILE_NAME_PROPERTY);
        System.clearProperty("LOG_FILE");
        System.clearProperty("LOG_PATH");
    }

    @DisplayName("local 프로필에서 MDC가 있는 로그 1건을 쓰면 지정 파일에 JSON 한 줄이 생기고 최상위에 MDC 키·level·message가 있고 그 밖에는 logstash 기본 필드만 있다")
    @Test
    void writesJsonLineWithMdcAtTopLevel_whenLocalProfile() throws IOException {
        Path logFile = tempDir.resolve("commerce-api.json");
        initialize("local", logFile);

        writeOneLogWithMdc();
        loggingSystem.cleanUp();

        assertThat(logFile).exists();
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        JsonNode json = objectMapper.readTree(lines.get(0));
        assertThat(json.path("level").asText()).isEqualTo("INFO");
        assertThat(json.path("message").asText()).isEqualTo("결제 경계 로그");
        for (String key : MDC_KEYS) {
            assertThat(json.path(key).asText()).as(key).isEqualTo(key + "-value");
        }
        List<String> fieldNames = new ArrayList<>();
        json.fieldNames().forEachRemaining(fieldNames::add);
        List<String> expectedFieldNames = new ArrayList<>(LOGSTASH_FIELDS);
        expectedFieldNames.addAll(MDC_KEYS);
        assertThat(fieldNames).containsExactlyInAnyOrderElementsOf(expectedFieldNames);
    }

    @DisplayName("test 프로필에서는 로그를 써도 JSON 파일이 생기지 않는다")
    @Test
    void doesNotCreateJsonFile_whenTestProfile() {
        Path logFile = tempDir.resolve("commerce-api.json");
        initialize("test", logFile);

        writeOneLogWithMdc();
        loggingSystem.cleanUp();

        assertThat(logFile).doesNotExist();
    }

    private void initialize(String profile, Path logFile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("spring.application.name", "commerce-api");
        environment.setProperty(LogFile.FILE_NAME_PROPERTY, logFile.toString());
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), CONFIG_LOCATION, LogFile.get(environment));
    }

    private void writeOneLogWithMdc() {
        Logger logger = LoggerFactory.getLogger("com.loopers.logging.Probe");
        for (String key : MDC_KEYS) {
            MDC.put(key, key + "-value");
        }
        logger.info("결제 경계 로그");
        MDC.clear();
    }
}
