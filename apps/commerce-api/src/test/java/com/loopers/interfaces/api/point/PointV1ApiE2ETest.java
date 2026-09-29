package com.loopers.interfaces.api.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointHistory;
import com.loopers.domain.point.PointHistoryType;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.point.PointGroupJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Period;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PointV1ApiE2ETest {

    private static final String CHARGE = "/api/v1/points/charge";
    private static final String BALANCE = "/api/v1/points";
    private static final String USER_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PointGroupJpaRepository pointGroupJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("고객"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void charge(long amount, long expectedBalance) throws Exception {
        mockMvc.perform(post(CHARGE).header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(expectedBalance));
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {

        @DisplayName("PNT-02 잔액 0에서 10,000원을 충전하면 잔액 10,000원을 돌려주고, 그룹 1개와 충전 이력 +10,000이 저장된다.")
        @Test
        void chargesAndStoresGroupAndHistory() throws Exception {
            // act
            charge(10_000, 10_000);

            // assert
            PointGroup group = pointGroupJpaRepository.findAll().get(0);
            assertThat(group.getRemaining()).isEqualTo(Money.of(10_000));
            PointHistory history = pointHistoryJpaRepository.findAll().get(0);
            assertThat(history.getType()).isEqualTo(PointHistoryType.CHARGE);
            assertThat(history.getAmount()).isEqualTo(10_000L);
            assertThat(history.getGroupId()).isEqualTo(group.getId());
        }

        @DisplayName("PNT-01·PNT-03 10,000원 뒤 5,000원을 충전하면 잔액은 15,000원이고, 그룹은 2개다.")
        @Test
        void accumulatesCharges() throws Exception {
            // act
            charge(10_000, 10_000);
            charge(5_000, 15_000);

            // assert
            assertThat(pointGroupJpaRepository.count()).isEqualTo(2);
            mockMvc.perform(get(BALANCE).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.balance").value(15_000));
        }

        @DisplayName("PNT-02 0·-1·누락·\"abc\"·1.5·long 범위 초과 충전액은 400이고, 그룹·이력이 생기지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "{\"amount\": 0}",
            "{\"amount\": -1}",
            "{}",
            "{\"amount\": \"abc\"}",
            "{\"amount\": 1.5}",
            "{\"amount\": 9223372036854775808}"
        })
        void rejectsInvalidAmount(String body) throws Exception {
            // act
            mockMvc.perform(post(CHARGE).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("Bad Request"));

            // assert
            assertThat(pointGroupJpaRepository.count()).isZero();
            assertThat(pointHistoryJpaRepository.count()).isZero();
        }

        @DisplayName("PNT-03 충전 후 잔액이 long 범위를 넘으면 409이고, 새 그룹·이력이 생기지 않는다.")
        @Test
        void rejectsBalanceOverflow() throws Exception {
            // arrange
            pointGroupJpaRepository.save(PointGroup.charge(user.getId(), Money.of(Long.MAX_VALUE - 1), ZonedDateTime.now(), Period.ofYears(5)));

            // act
            mockMvc.perform(post(CHARGE).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": 2}"))
                .andExpect(status().isConflict());

            // assert
            assertThat(pointGroupJpaRepository.count()).isEqualTo(1);
            assertThat(pointHistoryJpaRepository.count()).isZero();
        }

        @DisplayName("USR-01 X-USER-ID가 없으면 401이고, 그룹이 생기지 않는다.")
        @Test
        void rejectsUnidentifiedRequest() throws Exception {
            // act
            mockMvc.perform(post(CHARGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": 1000}"))
                .andExpect(status().isUnauthorized());

            // assert
            assertThat(pointGroupJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class Balance {

        @DisplayName("PNT-01 충전한 적이 없으면 잔액은 0원이다.")
        @Test
        void returnsZeroForNewUser() throws Exception {
            // act & assert
            mockMvc.perform(get(BALANCE).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0));
        }

        @DisplayName("PNT-07 만료된 그룹과 다른 사용자의 포인트는 잔액에 들어가지 않는다.")
        @Test
        void excludesExpiredAndOthers() throws Exception {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            pointGroupJpaRepository.save(PointGroup.charge(user.getId(), Money.of(4_000), ZonedDateTime.now().minusYears(6), Period.ofYears(5)));
            pointGroupJpaRepository.save(PointGroup.charge(user.getId(), Money.of(5_000), ZonedDateTime.now(), Period.ofYears(5)));
            pointGroupJpaRepository.save(PointGroup.charge(other.getId(), Money.of(9_000), ZonedDateTime.now(), Period.ofYears(5)));

            // act & assert
            mockMvc.perform(get(BALANCE).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.balance").value(5_000));
        }
    }
}
