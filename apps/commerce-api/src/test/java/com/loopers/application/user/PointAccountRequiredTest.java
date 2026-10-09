package com.loopers.application.user;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
class PointAccountRequiredTest {
    @Autowired private MockMvc mvc;
    @Autowired private PointFacade points;
    @Autowired private UserJpaRepository users;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @Test
    void missingAccountReturnsHttp500WithoutCreatingIt() throws Exception {
        Long userId = users.save(new UserModel()).getId();
        mvc.perform(get("/api/v1/points").header("X-USER-ID", userId))
            .andExpect(status().isInternalServerError());
        mvc.perform(post("/api/v1/points/charge").header("X-USER-ID", userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
            .andExpect(status().isInternalServerError());
        assertThat(balances.findByUserId(userId)).isEmpty();
    }

    @Test
    void missingAccountIsAnInternalErrorAndIsNotCreatedByLookupOrCharge() {
        Long userId = users.save(new UserModel()).getId();
        assertThatThrownBy(() -> points.getBalance(userId))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));
        assertThatThrownBy(() -> points.charge(userId, 1_000L))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));
        assertThat(balances.findByUserId(userId)).isEmpty();
    }
}
