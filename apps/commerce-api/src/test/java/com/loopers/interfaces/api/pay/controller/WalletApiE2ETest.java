package com.loopers.interfaces.api.pay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.domain.pay.model.PointBillType;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.infrastructure.persistence.pay.entity.QPointBillJpaEntity;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.pay.dto.WalletApiDto;
import com.loopers.support.test.E2ETest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@E2ETest
class WalletApiE2ETest {
    private static final QPointBillJpaEntity BILL = QPointBillJpaEntity.pointBillJpaEntity;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
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

        @DisplayName("0 이하 충전액이면 400을 반환한다")
        @Test
        void returnsBadRequest_whenAmountIsNotPositive() {
            createUserWithWallet(1L);

            ResponseEntity<ApiResponse<Object>> response = chargeRaw(0L, "1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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

    private HttpHeaders headers(String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (userIdHeader != null) {
            headers.add("X-USER-ID", userIdHeader);
        }
        return headers;
    }

    private long countChargeBills(long userId) {
        return queryFactory.select(BILL.count()).from(BILL)
            .where(BILL.userId.eq(userId), BILL.type.eq(PointBillType.CHARGE))
            .fetchOne();
    }
}
