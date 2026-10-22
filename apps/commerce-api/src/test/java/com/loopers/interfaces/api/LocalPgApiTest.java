package com.loopers.interfaces.api;

import com.loopers.interfaces.api.pg.LocalPgDto;
import com.loopers.interfaces.api.pg.LocalPgStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalPgApiTest {

    private static final String PROVIDER_ID = "135135";
    private static final String PG_URL = "/api/v1/payments";

    @Autowired TestRestTemplate restTemplate;
    @Autowired LocalPgStore store;

    @AfterEach
    void tearDown() {
        store.clear();
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-USER-ID", PROVIDER_ID);
        return h;
    }

    @DisplayName("정상 결제 요청 시 202 PENDING을 반환하고, GET으로 조회하면 SUCCESS를 확인할 수 있다.")
    @Test
    void pay_success_thenQueryConfirms() {
        var request = new LocalPgDto.PaymentRequest("LP-ORD-0000000001", "CARD", "1234-5678", 10_000L, "http://localhost/cb");

        ResponseEntity<LocalPgDto.PaymentResponse> postResponse = restTemplate.exchange(
            PG_URL, HttpMethod.POST, new HttpEntity<>(request, headers()), LocalPgDto.PaymentResponse.class
        );

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(postResponse.getBody()).isNotNull();
        assertThat(postResponse.getBody().status()).isEqualTo("PENDING");

        String txKey = postResponse.getBody().transactionKey();
        ResponseEntity<LocalPgDto.PaymentResponse> getResponse = restTemplate.exchange(
            PG_URL + "/" + txKey, HttpMethod.GET, new HttpEntity<>(headers()), LocalPgDto.PaymentResponse.class
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().status()).isEqualTo("SUCCESS");
    }

    @DisplayName("amount==3(FAILED 시나리오) 결제 시 GET으로 조회하면 FAILED 상태를 반환한다.")
    @Test
    void pay_failed_scenario() {
        var request = new LocalPgDto.PaymentRequest("LP-ORD-0000000002", "CARD", "1234-5678", LocalPgStore.SCENARIO_FAILED, "http://localhost/cb");

        ResponseEntity<LocalPgDto.PaymentResponse> postResponse = restTemplate.exchange(
            PG_URL, HttpMethod.POST, new HttpEntity<>(request, headers()), LocalPgDto.PaymentResponse.class
        );

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String txKey = postResponse.getBody().transactionKey();
        ResponseEntity<LocalPgDto.PaymentResponse> getResponse = restTemplate.exchange(
            PG_URL + "/" + txKey, HttpMethod.GET, new HttpEntity<>(headers()), LocalPgDto.PaymentResponse.class
        );

        assertThat(getResponse.getBody().status()).isEqualTo("FAILED");
    }

    @DisplayName("amount==1(REJECTED 시나리오) 결제 시 422를 반환하고 거래가 생성되지 않는다.")
    @Test
    void pay_rejected_returns422() {
        var request = new LocalPgDto.PaymentRequest("LP-ORD-0000000003", "CARD", "1234-5678", LocalPgStore.SCENARIO_REJECTED, "http://localhost/cb");

        ResponseEntity<LocalPgDto.RejectedResponse> response = restTemplate.exchange(
            PG_URL, HttpMethod.POST, new HttpEntity<>(request, headers()), LocalPgDto.RejectedResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().transactionCreated()).isFalse();
    }

    @DisplayName("orderId로 조회 시 해당 주문의 거래 목록을 반환한다.")
    @Test
    void getByOrderId_returnsList() {
        String orderId = "LP-ORD-0000000004";
        var request = new LocalPgDto.PaymentRequest(orderId, "CARD", "1234-5678", 10_000L, "http://localhost/cb");

        restTemplate.exchange(PG_URL, HttpMethod.POST, new HttpEntity<>(request, headers()), LocalPgDto.PaymentResponse.class);

        ParameterizedTypeReference<List<LocalPgDto.PaymentResponse>> listType = new ParameterizedTypeReference<>() {};
        ResponseEntity<List<LocalPgDto.PaymentResponse>> listResponse = restTemplate.exchange(
            PG_URL + "?orderId=" + orderId, HttpMethod.GET, new HttpEntity<>(headers()), listType
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);
        assertThat(listResponse.getBody().get(0).orderId()).isEqualTo(orderId);
    }

    @DisplayName("존재하지 않는 transactionKey 조회 시 404를 반환한다.")
    @Test
    void getByTransactionKey_notFound() {
        ResponseEntity<LocalPgDto.PaymentResponse> response = restTemplate.exchange(
            PG_URL + "/TX-NOT-EXIST", HttpMethod.GET, new HttpEntity<>(headers()), LocalPgDto.PaymentResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @DisplayName("X-USER-ID 헤더가 없으면 401을 반환한다.")
    @Test
    void pay_unauthorized_withoutHeader() {
        var request = new LocalPgDto.PaymentRequest("LP-ORD-0000000005", "CARD", "1234-5678", 10_000L, "http://localhost/cb");

        ResponseEntity<Void> response = restTemplate.postForEntity(PG_URL, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
