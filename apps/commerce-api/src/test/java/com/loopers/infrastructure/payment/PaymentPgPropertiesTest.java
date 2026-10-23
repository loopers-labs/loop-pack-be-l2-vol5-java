package com.loopers.infrastructure.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentPgPropertiesTest {

    private static final String LOCAL_BASE_URL = "http://127.0.0.1:8082";
    private static final String LOCAL_CALLBACK_URL = "http://127.0.0.1:8080/api/v1/payments/callback";

    private PaymentPgProperties properties(String baseUrl, String callbackUrl, Duration timeout) {
        return new PaymentPgProperties(baseUrl, callbackUrl, "135135", timeout, timeout, timeout);
    }

    @DisplayName("PG 연결 설정을 만들 때,")
    @Nested
    class Create {
        @DisplayName("로컬 주소(127.0.0.1, localhost)와 양수 timeout 이면 만들어진다.")
        @ParameterizedTest
        @ValueSource(strings = {"http://127.0.0.1:8082", "http://localhost:8082"})
        void createsProperties_whenLocal(String baseUrl) {
            // act
            PaymentPgProperties properties = properties(baseUrl, LOCAL_CALLBACK_URL, Duration.ofSeconds(1));

            // assert
            assertThat(properties.baseUrl()).isEqualTo(baseUrl);
        }

        @DisplayName("PG 주소가 로컬이 아니면 거절한다. 외부 주소를 설정으로도 쓸 수 없다.")
        @ParameterizedTest
        @ValueSource(strings = {"https://pg.example.com", "http://10.0.0.5:8082", "http://127.0.0.1.evil.com", "not-a-url"})
        void rejects_whenBaseUrlIsNotLocal(String baseUrl) {
            assertThrows(
                IllegalArgumentException.class,
                () -> properties(baseUrl, LOCAL_CALLBACK_URL, Duration.ofSeconds(1))
            );
        }

        @DisplayName("콜백 주소가 로컬이 아니거나 비어 있으면 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {"https://evil.example.com/callback", ""})
        void rejects_whenCallbackUrlIsNotLocal(String callbackUrl) {
            assertThrows(
                IllegalArgumentException.class,
                () -> properties(LOCAL_BASE_URL, callbackUrl, Duration.ofSeconds(1))
            );
        }

        @DisplayName("timeout 이 0 이하이면 거절한다.")
        @Test
        void rejects_whenTimeoutIsNotPositive() {
            assertThrows(
                IllegalArgumentException.class,
                () -> properties(LOCAL_BASE_URL, LOCAL_CALLBACK_URL, Duration.ZERO)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> properties(LOCAL_BASE_URL, LOCAL_CALLBACK_URL, Duration.ofSeconds(-1))
            );
        }
    }
}
