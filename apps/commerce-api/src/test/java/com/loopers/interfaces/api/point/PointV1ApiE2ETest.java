package com.loopers.interfaces.api.point;

import com.loopers.domain.user.User;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PointV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String CHARGE = "/api/v1/points/charge";
    private static final String BALANCE = "/api/v1/points";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User user;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResultActions charge(String body) throws Exception {
        return mvc.perform(post(CHARGE)
            .header(USER_ID_HEADER, String.valueOf(user.getId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private ResultActions balance() throws Exception {
        return mvc.perform(get(BALANCE).header(USER_ID_HEADER, String.valueOf(user.getId())));
    }

    @DisplayName("충전한 적이 없으면, 잔액 0 원을 돌려준다.")
    @Test
    void returnsZeroBalance_whenNeverCharged() throws Exception {
        balance()
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(0));
    }

    @DisplayName("충전하면 충전 후 잔액을 돌려주고, 저장된 잔액이 다시 조회된다.")
    @Test
    void chargesAndPersists() throws Exception {
        charge("{\"amount\": 10000}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10_000));
        charge("{\"amount\": 5000}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(15_000));

        balance().andExpect(jsonPath("$.data.balance").value(15_000));
    }

    @DisplayName("0 이하를 충전하면, 400 과 INVALID_CHARGE_AMOUNT 를 돌려주고 잔액은 그대로다.")
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void rejectsNonPositiveAmount(String amount) throws Exception {
        charge("{\"amount\": 1000}").andExpect(status().isOk());

        charge("{\"amount\": " + amount + "}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("INVALID_CHARGE_AMOUNT"));

        balance().andExpect(jsonPath("$.data.balance").value(1_000));
    }

    @DisplayName("충전액이 없거나, 숫자가 아니거나, 표현 범위를 넘으면 400 과 BAD_REQUEST 를 돌려주고 행이 생기지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"amount\": \"abc\"}", "{\"amount\": 1.5}", "{\"amount\": 99999999999999999999}"})
    void rejectsMalformedAmount(String body) throws Exception {
        charge(body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));

        assertThat(pointJpaRepository.count()).isZero();
    }

    @DisplayName("충전 후 잔액이 표현 범위를 넘으면, 409 와 BALANCE_LIMIT_EXCEEDED 를 돌려주고 잔액은 그대로다.")
    @Test
    void rejectsOverflow() throws Exception {
        charge("{\"amount\": " + Long.MAX_VALUE + "}").andExpect(status().isOk());

        charge("{\"amount\": 1}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.meta.errorCode").value("BALANCE_LIMIT_EXCEEDED"));

        balance().andExpect(jsonPath("$.data.balance").value(Long.MAX_VALUE));
    }

    @DisplayName("식별이 없으면, 충전과 조회 모두 401 과 UNAUTHENTICATED 를 돌려준다.")
    @Test
    void returnsUnauthenticated_whenHeaderIsMissing() throws Exception {
        mvc.perform(post(CHARGE).contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1000}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.meta.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get(BALANCE))
            .andExpect(status().isUnauthorized());

        assertThat(pointJpaRepository.count()).isZero();
    }
}
