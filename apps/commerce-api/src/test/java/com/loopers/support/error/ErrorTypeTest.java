package com.loopers.support.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8 §8-2. 429 는 "잠시 후 같은 요청을 다시 보내라", 503 은 "서버 쪽 문제, 잠시 후 다시 보내라" 를 뜻한다.
 * code 는 기존 값들과 같이 HTTP reason phrase 를 그대로 쓴다.
 */
class ErrorTypeTest {

    @DisplayName("TOO_MANY_REQUESTS 는 429 이고 code 는 reason phrase, message 는 재시도를 안내한다.")
    @Test
    void tooManyRequests() {
        ErrorType type = ErrorType.TOO_MANY_REQUESTS;

        assertThat(type.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(type.getCode()).isEqualTo("Too Many Requests");
        assertThat(type.getMessage()).contains("잠시 후 다시 시도");
    }

    @DisplayName("SERVICE_UNAVAILABLE 은 503 이고 code 는 reason phrase, message 는 재시도를 안내한다.")
    @Test
    void serviceUnavailable() {
        ErrorType type = ErrorType.SERVICE_UNAVAILABLE;

        assertThat(type.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(type.getCode()).isEqualTo("Service Unavailable");
        assertThat(type.getMessage()).contains("잠시 후 다시 시도");
    }
}
