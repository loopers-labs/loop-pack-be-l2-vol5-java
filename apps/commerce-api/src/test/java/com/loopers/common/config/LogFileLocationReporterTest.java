package com.loopers.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

// 앱이 실제로 쓰는 파일 경로는 설정 값이 아니라 초기화된 logback root의 파일 appender에서 읽어야 한다(w10s7 1b).
// 같은 JVM의 다른 테스트가 쓰는 logback 설정을 초기화하지 않으려고, test 프로필 설정(파일 appender 없음) 위에
// 파일 appender 하나를 잠시 붙였다 뗀다. local 프로필의 파일 appender 구성 자체는 supports/logging의 LocalJsonFileLogTest가 본다.
class LogFileLocationReporterTest {
    @TempDir
    Path tempDir;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private FileAppender<ILoggingEvent> fileAppender;

    @BeforeEach
    void captureReporter() {
        logs.start();
        reporterLogger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        reporterLogger().detachAppender(logs);
        if (fileAppender != null) {
            rootLogger().detachAppender(fileAppender);
            fileAppender.stop();
        }
    }

    @Test
    @DisplayName("root에 파일 appender가 붙어 있으면 기동 보고는 그 appender가 쓰는 절대 경로를 INFO 한 줄로 남긴다")
    void reportsAbsoluteFilePath_whenFileLogEnabled() {
        Path logFile = tempDir.resolve("commerce-api.json");
        attachFileAppender(logFile);

        new LogFileLocationReporter().report();

        List<ILoggingEvent> lines = logs.list;
        assertThat(lines).as("보고 줄 수").hasSize(1);
        assertThat(lines.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(lines.get(0).getFormattedMessage()).contains("path=" + logFile.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("test 프로필처럼 파일 로그가 꺼져 있으면 기동 보고는 아무 줄도 남기지 않는다")
    void reportsNothing_whenFileLogDisabled() {
        new LogFileLocationReporter().report();

        assertThat(logs.list).isEmpty();
    }

    private void attachFileAppender(Path logFile) {
        Logger root = rootLogger();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(root.getLoggerContext());
        encoder.setPattern("%msg%n");
        encoder.start();
        fileAppender = new FileAppender<>();
        fileAppender.setContext(root.getLoggerContext());
        fileAppender.setName("FILE");
        fileAppender.setFile(logFile.toString());
        fileAppender.setEncoder(encoder);
        fileAppender.start();
        root.addAppender(fileAppender);
    }

    private static Logger reporterLogger() {
        return (Logger) LoggerFactory.getLogger(LogFileLocationReporter.class);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
