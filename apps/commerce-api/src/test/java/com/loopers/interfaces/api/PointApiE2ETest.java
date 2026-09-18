package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.interfaces.api.point.ChargePointController.ChargeRequest;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointApiE2ETest {
    @Autowired
    private PointBalanceRepository points;

    @Autowired
    private TestRestTemplate rest;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DatabaseCleanUp cleanUp;

    @Test
    @DisplayName("식별 헤더가 없으면 잔액 조회를 거절한다")
    void rejectsMissingIdentity() {
        // arrange
        HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

        // act
        var response = rest.exchange("/api/v1/points", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("잔액 행이 없으면 숫자 0을 반환하고 저장하지 않는다")
    void readsZeroWithoutCreatingBalance() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
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
    @DisplayName("첫 충전 금액을 잔액으로 저장한다")
    void persistsFirstCharge() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(new ChargeRequest(10_000L), headers);

        // act
        var response = rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(10_000);
        PointBalance stored = points.findByUserId(1).orElseThrow();
        assertThat(stored.getBalance()).isEqualTo(10_000);
        assertThat(entityManager.createQuery("select count(e) from PointBalanceJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 10,000원에 5,000원을 충전하면 15,000원을 저장한다")
    void addsChargeToStoredBalance() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(new ChargeRequest(5_000L), headers);

        // act
        var response = rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode actualBalance = response.getBody().requiredAt("/data/balance");
        assertThat(actualBalance.isIntegralNumber()).isTrue();
        assertThat(actualBalance.longValue()).isEqualTo(15_000);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("저장된 본인 잔액을 조회한다")
    void readsStoredBalance() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
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
    @DisplayName("다른 사용자의 잔액은 본인 조회에 포함하지 않는다")
    void readsOnlyRequestersBalance() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
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
    @ValueSource(strings = {"{}", "{\"amount\":null}", "{\"amount\":0}", "{\"amount\":-1}",
        "{\"amount\":\"oops\"}", "{\"amount\":\"100\"}", "{\"amount\":1.5}", "{\"amount\":9223372036854775808}"})
    @DisplayName("잘못된 충전 입력은 기존 잔액을 변경하지 않는다")
    void rejectsInvalidChargeWithoutChangingBalance(String invalidBody) {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(invalidBody, headers);

        // act
        var response = rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("합산 범위를 넘는 충전은 저장된 잔액을 유지한다")
    void rejectsOverflowWithoutChangingBalance() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(Long.MAX_VALUE);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var request = new HttpEntity<>(new ChargeRequest(1L), headers);

        // act
        var response = rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    @DisplayName("유효하지 않은 사용자 식별자로 충전할 수 없다")
    void rejectsInvalidIdentity(String identity) {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", identity);
        var request = new HttpEntity<>(new ChargeRequest(100L), headers);

        // act
        var response = rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(entityManager.createQuery("select count(e) from PointBalanceJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 사용자는 충전할 수 없다")
    void rejectsUnknownUser() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "999");
        var request = new HttpEntity<>(new ChargeRequest(100L), headers);

        // act
        var response = rest.exchange("/api/v1/points/charge", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("USER_NOT_FOUND");
        assertThat(points.findByUserId(999)).isEmpty();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
