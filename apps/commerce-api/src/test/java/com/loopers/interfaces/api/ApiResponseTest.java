package com.loopers.interfaces.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8 §8-1. 202 접수 응답은 "접수됨 ≠ 발급 보장" 을 meta.message 로 알린다. 그 외 성공 응답은 기존처럼 message 가 null 이다.
 */
class ApiResponseTest {

    @DisplayName("success(data, message) 는 SUCCESS 결과에 data 와 message 를 함께 담는다.")
    @Test
    void successWithMessage() {
        ApiResponse<String> response = ApiResponse.success("payload", "접수되었습니다. 발급이 보장된 것은 아닙니다.");

        assertThat(response.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
        assertThat(response.meta().errorCode()).isNull();
        assertThat(response.meta().message()).isEqualTo("접수되었습니다. 발급이 보장된 것은 아닙니다.");
        assertThat(response.data()).isEqualTo("payload");
    }

    @DisplayName("success(data) 는 기존대로 message 가 null 이다.")
    @Test
    void successWithoutMessage() {
        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
        assertThat(response.meta().message()).isNull();
        assertThat(response.data()).isEqualTo("payload");
    }
}
