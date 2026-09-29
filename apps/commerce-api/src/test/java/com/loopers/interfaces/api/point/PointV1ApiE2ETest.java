package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    private static final String ENDPOINT_CHARGE = "/api/v1/points/charge";
    private static final String ENDPOINT_BALANCE = "/api/v1/points";
    private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(1_000);

    private final TestRestTemplate testRestTemplate;

    @Autowired
    PointV1ApiE2ETest(TestRestTemplate testRestTemplate) {
        this.testRestTemplate = testRestTemplate;
    }

    private static final ParameterizedTypeReference<ApiResponse<PointV1Dto.BalanceResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private HttpHeaders headersOf(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", String.valueOf(userId));
        }
        return headers;
    }

    private ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> charge(Long userId, Object body) {
        return testRestTemplate.exchange(
            ENDPOINT_CHARGE, HttpMethod.POST, new HttpEntity<>(body, headersOf(userId)), RESPONSE_TYPE);
    }

    @DisplayName("충전하면 200 과 충전 후 잔액을 돌려주고, 이어서 조회해도 같은 잔액이다.")
    @Test
    void returnsBalanceAfterCharge() {
        Long userId = USER_ID_SEQUENCE.incrementAndGet();

        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> charged = charge(userId, Map.of("amount", 10_000));
        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> read = testRestTemplate.exchange(
            ENDPOINT_BALANCE, HttpMethod.GET, new HttpEntity<>(null, headersOf(userId)), RESPONSE_TYPE);

        assertAll(
            () -> assertThat(charged.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(charged.getBody()).isNotNull(),
            () -> assertThat(charged.getBody().data().balance()).isEqualTo(10_000L),
            () -> assertThat(read.getBody().data().balance()).isEqualTo(10_000L)
        );
    }

    @DisplayName("충전한 적 없는 유저의 잔액은 0원이다.")
    @Test
    void returnsZeroForUserWithoutRecord() {
        Long userId = USER_ID_SEQUENCE.incrementAndGet();

        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> read = testRestTemplate.exchange(
            ENDPOINT_BALANCE, HttpMethod.GET, new HttpEntity<>(null, headersOf(userId)), RESPONSE_TYPE);

        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody().data().balance()).isZero();
    }

    @DisplayName("X-USER-ID 가 없으면 인증 실패가 아니라 입력 오류라 400 이다.")
    @Test
    void returnsBadRequestWhenUserHeaderIsMissing() {
        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> read = testRestTemplate.exchange(
            ENDPOINT_BALANCE, HttpMethod.GET, new HttpEntity<>(null, headersOf(null)), RESPONSE_TYPE);

        assertAll(
            () -> assertThat(read.getStatusCode().value()).isEqualTo(400),
            () -> assertThat(read.getBody().meta().errorCode()).isEqualTo("Bad Request")
        );
    }

    @DisplayName("충전액이 누락·0 이하·잘못된 타입이면 컨트롤러 앞에서 400 으로 걸린다.")
    @Test
    void returnsBadRequestForMalformedAmount() {
        Long userId = USER_ID_SEQUENCE.incrementAndGet();

        assertAll(
            () -> assertThat(charge(userId, Map.of()).getStatusCode().value()).isEqualTo(400),
            () -> assertThat(charge(userId, Map.of("amount", 0)).getStatusCode().value()).isEqualTo(400),
            () -> assertThat(charge(userId, Map.of("amount", -1)).getStatusCode().value()).isEqualTo(400),
            () -> assertThat(charge(userId, Map.of("amount", "abc")).getStatusCode().value()).isEqualTo(400)
        );
    }

    @DisplayName("잔액 합산이 표현 범위를 넘으면 409 와 업무 코드를 돌려주고 잔액은 그대로다.")
    @Test
    void returnsConflictWhenBalanceWouldOverflow() {
        Long userId = USER_ID_SEQUENCE.incrementAndGet();
        charge(userId, Map.of("amount", Long.MAX_VALUE - 10));

        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> exceeded = charge(userId, Map.of("amount", 11));
        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> read = testRestTemplate.exchange(
            ENDPOINT_BALANCE, HttpMethod.GET, new HttpEntity<>(null, headersOf(userId)), RESPONSE_TYPE);

        assertAll(
            () -> assertThat(exceeded.getStatusCode().value()).isEqualTo(409),
            () -> assertThat(exceeded.getBody().meta().errorCode()).isEqualTo("POINT_BALANCE_EXCEEDED"),
            () -> assertThat(read.getBody().data().balance()).isEqualTo(Long.MAX_VALUE - 10)
        );
    }
}
