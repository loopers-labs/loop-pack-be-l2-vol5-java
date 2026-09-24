package com.loopers.interfaces.api.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletApiE2ETest {
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("포인트 충전")
    @Nested
    class Charge {
        @DisplayName("존재하는 사용자면 200을 반환하고 잔액을 늘리며 CHARGE 기록을 남긴다")
        @Test
        void chargesWallet() {
            createUserWithWallet(1L);

            ResponseEntity<ApiResponse<WalletApiDto.BalanceResponse>> response = charge(1_000L, "1");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(1_000L),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(1_000L),
                () -> assertThat(countChargeBills(1L)).isEqualTo(1L)
            );
        }

        @DisplayName("0 이하 충전액이면 400을 반환하고 잔액을 유지한다")
        @Test
        void returnsBadRequest_whenAmountIsNotPositive() {
            createUserWithWallet(1L);

            ResponseEntity<ApiResponse<Object>> response = chargeRaw(0L, "1");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isZero(),
                () -> assertThat(countChargeBills(1L)).isZero()
            );
        }

        @DisplayName("충전 후 잔액이 범위를 초과하면 400을 반환하고 잔액을 유지한다")
        @Test
        void returnsBadRequest_whenBalanceOverflows() {
            userRepository.save(User.create(1L));
            walletRepository.save(Wallet.restore(1L, Long.MAX_VALUE));

            ResponseEntity<ApiResponse<Object>> response = chargeRaw(1L, "1");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(Long.MAX_VALUE),
                () -> assertThat(countChargeBills(1L)).isZero()
            );
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400을 반환한다")
        @Test
        void returnsBadRequest_whenHeaderIsMissing() {
            ResponseEntity<ApiResponse<Object>> response = chargeRaw(1_000L, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("X-USER-ID 헤더가 양의 정수가 아니면 400을 반환한다")
        @Test
        void returnsBadRequest_whenHeaderIsInvalid() {
            ResponseEntity<ApiResponse<Object>> response = chargeRaw(1_000L, "abc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("존재하지 않는 사용자면 404를 반환한다")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            ResponseEntity<ApiResponse<Object>> response = chargeRaw(1_000L, "999");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("포인트 잔액 조회")
    @Nested
    class FindBalance {
        @DisplayName("존재하는 사용자면 200을 반환하고 저장된 잔액을 반환한다")
        @Test
        void returnsBalance() {
            userRepository.save(User.create(1L));
            Wallet wallet = Wallet.zero(1L);
            wallet.charge(Money.positive(2_000L));
            walletRepository.save(wallet);

            ResponseEntity<ApiResponse<WalletApiDto.BalanceResponse>> response = findBalance("1");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(2_000L)
            );
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400을 반환한다")
        @Test
        void returnsBadRequest_whenHeaderIsMissing() {
            ResponseEntity<ApiResponse<Object>> response = findBalanceRaw(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("X-USER-ID 헤더가 양의 정수가 아니면 400을 반환한다")
        @Test
        void returnsBadRequest_whenHeaderIsInvalid() {
            ResponseEntity<ApiResponse<Object>> response = findBalanceRaw("abc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("존재하지 않는 사용자면 404를 반환한다")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            ResponseEntity<ApiResponse<Object>> response = findBalanceRaw("999");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private void createUserWithWallet(long userId) {
        userRepository.save(User.create(userId));
        walletRepository.save(Wallet.zero(userId));
    }

    private ResponseEntity<ApiResponse<WalletApiDto.BalanceResponse>> charge(long amount, String userIdHeader) {
        return restTemplate.exchange(
            "/api/v1/points/charge",
            HttpMethod.POST,
            new HttpEntity<>(new WalletApiDto.ChargeRequest(amount), headers(userIdHeader)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<Object>> chargeRaw(long amount, String userIdHeader) {
        return restTemplate.exchange(
            "/api/v1/points/charge",
            HttpMethod.POST,
            new HttpEntity<>(new WalletApiDto.ChargeRequest(amount), headers(userIdHeader)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<WalletApiDto.BalanceResponse>> findBalance(String userIdHeader) {
        return restTemplate.exchange(
            "/api/v1/points",
            HttpMethod.GET,
            new HttpEntity<>(null, headers(userIdHeader)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<Object>> findBalanceRaw(String userIdHeader) {
        return restTemplate.exchange(
            "/api/v1/points",
            HttpMethod.GET,
            new HttpEntity<>(null, headers(userIdHeader)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private HttpHeaders headers(String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (userIdHeader != null) {
            headers.add("X-USER-ID", userIdHeader);
        }
        return headers;
    }

    private long countChargeBills(long userId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM point_bills WHERE user_id = :userId AND type = 'CHARGE'")
            .param("userId", userId)
            .query(Long.class)
            .single();
    }
}
