package com.loopers.user.interfaces;

import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class PointHttpTest {

    private static final String CHARGE = "/api/v1/points/charge";
    private static final String BALANCE = "/api/v1/points";
    private static final long ALMOST_MAX_BALANCE = Long.MAX_VALUE - 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[R-POINT-01] 고객은 자신의 포인트를 충전할 수 있다.")
    @Nested
    class ChargeOwnPoint {

        @DisplayName("[동등 클래스 분할] 고객 A가 1,000을 충전하면 200이고 A의 잔액만 1,000이 되며 고객 B의 잔액은 0 그대로다.")
        @Test
        void chargesOnlyRequester() throws Exception {
            // arrange
            User requester = fixture.user();
            User other = fixture.user();

            // act
            mockMvc.perform(charge(requester, "{\"amount\":1000}"))
                .andExpect(success(HttpStatus.OK));

            // assert
            assertAll(
                () -> assertThat(balanceOf(requester)).isEqualTo(1_000L),
                () -> assertThat(balanceOf(other)).isZero()
            );
        }
    }

    @DisplayName("[R-POINT-02] 고객은 자신의 저장된 포인트 잔액을 조회할 수 있다.")
    @Nested
    class ReadOwnBalance {

        @DisplayName("[동등 클래스 분할] 잔액 2,000인 고객과 500인 고객은 잔액 조회에서 각자 자신의 저장된 잔액을 받는다.")
        @Test
        void returnsRequesterBalance() throws Exception {
            // arrange
            User first = fixture.userWithPoint(2_000L);
            User second = fixture.userWithPoint(500L);

            // act & assert
            mockMvc.perform(get(BALANCE).with(customer(first)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(2_000L));
            mockMvc.perform(get(BALANCE).with(customer(second)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(500L));
        }
    }

    @DisplayName("[R-POINT-04] 충전액은 양의 정수여야 한다.")
    @Nested
    class PositiveIntegerAmount {

        @DisplayName("[경계값 분석] 충전액 1은 200이고 잔액이 1 늘어난다.")
        @Test
        void acceptsOne() throws Exception {
            // arrange
            User customer = fixture.userWithPoint(1_000L);

            // act & assert
            mockMvc.perform(charge(customer, "{\"amount\":1}"))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(1_001L));
        }

        @DisplayName("[경계값 분석] 충전액 0과 -1은 400 INVALID_CHARGE_AMOUNT이다.")
        @ParameterizedTest(name = "충전액 {0}")
        @ValueSource(longs = {0L, -1L})
        void rejectsNonPositiveAmount(long amount) throws Exception {
            // arrange
            User customer = fixture.userWithPoint(1_000L);

            // act & assert
            mockMvc.perform(charge(customer, "{\"amount\":%d}".formatted(amount)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_CHARGE_AMOUNT"));
        }

        @DisplayName("[동등 클래스 분할] 정수가 아닌 충전액 1.5와 \"abc\"는 400 INVALID_REQUEST이다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"{\"amount\":1.5}", "{\"amount\":\"abc\"}"})
        void rejectsNonIntegerAmount(String requestBody) throws Exception {
            // arrange
            User customer = fixture.userWithPoint(1_000L);

            // act & assert
            mockMvc.perform(charge(customer, requestBody))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    @DisplayName("[R-POINT-06] 충전에 성공하면 기존 잔액에 충전액을 더하고 충전 후 잔액을 제공한다.")
    @Nested
    class AddAndReturnBalance {

        @DisplayName("[경계값 분석] 잔액 0에서 10,000을 충전하면 응답의 balance가 10,000이다.")
        @Test
        void chargesFromZero() throws Exception {
            // arrange
            User customer = fixture.user();

            // act & assert
            mockMvc.perform(charge(customer, "{\"amount\":10000}"))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(10_000L));
        }

        @DisplayName("[동등 클래스 분할] 잔액 3,000에서 2,000을 충전하면 응답의 balance가 5,000이고 이후 잔액 조회도 5,000이다.")
        @Test
        void addsToExistingBalance() throws Exception {
            // arrange
            User customer = fixture.userWithPoint(3_000L);

            // act
            mockMvc.perform(charge(customer, "{\"amount\":2000}"))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(5_000L));

            // assert
            assertThat(balanceOf(customer)).isEqualTo(5_000L);
        }
    }

    @DisplayName("[R-POINT-07] 충전 후 잔액은 시스템이 표현할 수 있는 범위를 넘을 수 없다.")
    @Nested
    class BalanceUpperBound {

        @DisplayName("[경계값 분석] 잔액 Long.MAX_VALUE - 1에서 1을 충전하면 200이고 잔액은 Long.MAX_VALUE다.")
        @Test
        void reachesMaxBalance() throws Exception {
            // arrange
            User customer = fixture.userWithPoint(ALMOST_MAX_BALANCE);

            // act & assert
            mockMvc.perform(charge(customer, "{\"amount\":1}"))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(Long.MAX_VALUE));
        }

        @DisplayName("[경계값 분석] 잔액 Long.MAX_VALUE - 1에서 2를 충전하면 409 POINT_BALANCE_LIMIT_EXCEEDED이다.")
        @Test
        void rejectsBalanceOverMax() throws Exception {
            // arrange
            User customer = fixture.userWithPoint(ALMOST_MAX_BALANCE);

            // act & assert
            mockMvc.perform(charge(customer, "{\"amount\":2}"))
                .andExpect(failure(HttpStatus.CONFLICT, "POINT_BALANCE_LIMIT_EXCEEDED"));
        }

        @DisplayName("[경계값 분석] 64비트 정수 범위를 넘는 충전액 9223372036854775808은 400 INVALID_REQUEST이다.")
        @Test
        void rejectsAmountOverLongRange() throws Exception {
            // arrange
            User customer = fixture.user();

            // act & assert
            mockMvc.perform(charge(customer, "{\"amount\":9223372036854775808}"))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    @DisplayName("[R-POINT-08] 유효하지 않은 충전 요청은 거절하고 기존 잔액을 유지한다.")
    @Nested
    class KeepBalanceOnInvalidCharge {

        @DisplayName("[오류 추측] 잔액 1,000에서 누락·깨진 JSON·0·문자열·범위 초과 충전을 거절하면 이후 잔액 조회는 1,000이다.")
        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "{}                                | INVALID_REQUEST",
            "{\"amount\":                      | INVALID_REQUEST",
            "{\"amount\":0}                    | INVALID_CHARGE_AMOUNT",
            "{\"amount\":\"abc\"}              | INVALID_REQUEST",
            "{\"amount\":9223372036854775808}  | INVALID_REQUEST"
        })
        void keepsBalance(String requestBody, String errorCode) throws Exception {
            // arrange
            User customer = fixture.userWithPoint(1_000L);

            // act
            mockMvc.perform(charge(customer, requestBody))
                .andExpect(failure(HttpStatus.BAD_REQUEST, errorCode));

            // assert
            assertThat(balanceOf(customer)).isEqualTo(1_000L);
        }

        @DisplayName("[경계값 분석] 잔액 Long.MAX_VALUE - 1에서 2 충전이 한도 초과로 거절되면 이후 잔액 조회는 그대로다.")
        @Test
        void keepsBalanceOnLimitExceeded() throws Exception {
            // arrange
            User customer = fixture.userWithPoint(ALMOST_MAX_BALANCE);

            // act
            mockMvc.perform(charge(customer, "{\"amount\":2}"))
                .andExpect(failure(HttpStatus.CONFLICT, "POINT_BALANCE_LIMIT_EXCEEDED"));

            // assert
            assertThat(balanceOf(customer)).isEqualTo(ALMOST_MAX_BALANCE);
        }
    }

    private MockHttpServletRequestBuilder charge(User customer, String requestBody) {
        return post(CHARGE)
            .with(customer(customer)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody);
    }

    private long balanceOf(User customer) throws Exception {
        MvcResult result = mockMvc.perform(get(BALANCE).with(customer(customer)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.balance").exists())
            .andReturn();
        return data(result).path("balance").asLong();
    }
}
