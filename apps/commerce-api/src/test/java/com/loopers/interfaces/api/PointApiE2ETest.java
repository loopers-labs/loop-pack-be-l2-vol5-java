package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.point.fixture.PointFixture;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.interfaces.api.point.ChargePointController.ChargeRequest;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointApiE2ETest {
    @Autowired private PointFixture fixture;
    @Autowired private UserFixture users;

    @Autowired private PointBalanceRepository points;

    @Autowired private TestRestTemplate rest;

    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 식별_헤더가_없으면_잔액_조회를_거절한다() {
        // arrange
        HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

        // act
        var response = rest.exchange("/api/v1/points", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 잔액_행이_없으면_숫자_0을_반환하고_저장하지_않는다() {
        // arrange
        users.createUser(1);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/points", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(0);
        assertThat(points.findByUserId(1)).isEmpty();
    }

    @Test
    void 첫_충전_금액을_잔액으로_저장한다() {
        // arrange
        users.createUser(1);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(new ChargeRequest(10_000L), headers);

        // act
        var response =
                rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(10_000);
        PointBalance stored = points.findByUserId(1).orElseThrow();
        assertThat(stored.getBalance()).isEqualTo(10_000);
        assertThat(fixture.rowCount()).isEqualTo(1);
    }

    @Test
    void 기존_잔액_10000원에_5000원을_충전하면_15000원을_저장한다() {
        // arrange
        users.createUser(1);
        fixture.createBalance(1, 10_000);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(new ChargeRequest(5_000L), headers);

        // act
        var response =
                rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(15_000);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(15_000);
    }

    @Test
    void 저장된_본인_잔액을_조회한다() {
        // arrange
        users.createUser(1);
        fixture.createBalance(1, 10_000);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/points", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(10_000);
    }

    @Test
    void 다른_사용자의_잔액은_본인_조회에_포함하지_않는다() {
        // arrange
        users.createUser(1);
        fixture.createBalance(1, 10_000);
        users.createUser(2);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/points", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(0);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"amount\":null}",
                "{\"amount\":0}",
                "{\"amount\":-1}",
                "{\"amount\":\"oops\"}",
                "{\"amount\":\"100\"}",
                "{\"amount\":1.5}",
                "{\"amount\":9223372036854775808}"
            })
    void 잘못된_충전_입력은_기존_잔액을_변경하지_않는다(String invalidBody) {
        // arrange
        users.createUser(1);
        fixture.createBalance(1, 10_000);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(invalidBody, headers);

        // act
        var response =
                rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 합산_범위를_넘는_충전은_저장된_잔액을_유지한다() {
        // arrange
        users.createUser(1);
        fixture.createBalance(1, Long.MAX_VALUE);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(new ChargeRequest(1L), headers);

        // act
        var response =
                rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("INVALID_REQUEST");
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void 유효하지_않은_사용자_식별자로_충전할_수_없다(String identity) {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", identity);
        var request = new HttpEntity<>(new ChargeRequest(100L), headers);

        // act
        var response =
                rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(fixture.rowCount()).isZero();
    }

    @Test
    void 존재하지_않는_사용자는_충전할_수_없다() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "999");
        var request = new HttpEntity<>(new ChargeRequest(100L), headers);

        // act
        var response =
                rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("USER_NOT_FOUND");
        assertThat(points.findByUserId(999)).isEmpty();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
