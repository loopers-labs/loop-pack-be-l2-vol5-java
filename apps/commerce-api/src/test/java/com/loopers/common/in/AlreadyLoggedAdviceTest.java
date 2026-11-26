package com.loopers.common.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.error.AlreadyLogged;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AlreadyLoggedAdviceTest {
    private static final String ORIGINAL = "원문 메시지";

    private final ApiControllerAdvice apiControllerAdvice = new ApiControllerAdvice();

    private final CommerceExceptionAdvice commerceExceptionAdvice = new CommerceExceptionAdvice();

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void attach() {
        logs = new ListAppender<>();
        logs.start();
        logger(ApiControllerAdvice.class).addAppender(logs);
        logger(CommerceExceptionAdvice.class).addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger(ApiControllerAdvice.class).detachAppender(logs);
        logger(CommerceExceptionAdvice.class).detachAppender(logs);
    }

    @Test
    @DisplayName("표식 없는 일반 오류는 지금처럼 500 응답을 주고 원문과 스택을 담은 ERROR 한 줄을 남긴다")
    void unmarkedFailureKeepsErrorWithOriginalAndStack() {
        IllegalStateException failure = new IllegalStateException(ORIGINAL);

        ResponseEntity<ApiResponse<?>> response = apiControllerAdvice.handle(failure);

        assertInternalError(response);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.get(0).getFormattedMessage()).contains(ORIGINAL);
        assertThat(logs.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    @DisplayName("이미 기록함 표식이 붙은 일반 오류는 같은 500 응답·본문을 주고 예외 타입·메시지를 바꾸지 않으며 로그를 남기지 않는다")
    void markedFailureKeepsResponseAndSkipsLog() {
        IllegalStateException failure = marked(new IllegalStateException(ORIGINAL));

        ResponseEntity<ApiResponse<?>> response = apiControllerAdvice.handle(failure);

        assertInternalError(response);
        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class).hasMessage(ORIGINAL);
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("표식 없는 잠금 실패는 지금처럼 503 응답을 주고 스택을 담은 경고 한 줄을 남긴다")
    void unmarkedLockFailureKeepsWarnWithStack() {
        PessimisticLockingFailureException failure = new PessimisticLockingFailureException(ORIGINAL);

        ResponseEntity<ApiResponse<Object>> response = commerceExceptionAdvice.lockFailure(failure);

        assertUnavailable(response);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    @DisplayName("이미 기록함 표식이 붙은 잠금 실패는 같은 503 응답·본문을 주고 예외 타입·메시지를 바꾸지 않으며 로그를 남기지 않는다")
    void markedLockFailureKeepsResponseAndSkipsLog() {
        PessimisticLockingFailureException failure = marked(new PessimisticLockingFailureException(ORIGINAL));

        ResponseEntity<ApiResponse<Object>> response = commerceExceptionAdvice.lockFailure(failure);

        assertUnavailable(response);
        assertThat(failure).isExactlyInstanceOf(PessimisticLockingFailureException.class).hasMessage(ORIGINAL);
        assertThat(logs.list).isEmpty();
    }

    private static <T extends Throwable> T marked(T failure) {
        failure.addSuppressed(new AlreadyLogged());
        return failure;
    }

    private static void assertInternalError(ResponseEntity<ApiResponse<?>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().meta().message()).isEqualTo("일시적인 오류가 발생했습니다.");
        assertThat(response.getBody().data()).isNull();
    }

    private static void assertUnavailable(ResponseEntity<ApiResponse<Object>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("Service Unavailable");
        assertThat(response.getBody().meta().message()).isEqualTo("잠시 후 다시 시도해 주세요.");
        assertThat(response.getBody().data()).isNull();
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }
}
