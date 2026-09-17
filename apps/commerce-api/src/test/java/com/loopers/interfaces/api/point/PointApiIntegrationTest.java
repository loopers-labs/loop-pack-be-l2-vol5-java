package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointApplicationService;
import com.loopers.infrastructure.user.UserJpaEntity;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class PointApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private PointApplicationService service;
    @Autowired private UserJpaRepository users;
    @Autowired private DatabaseCleanUp cleanup;
    @BeforeEach void prepare() { users.save(new UserJpaEntity(1)); }
    @AfterEach void clean() { cleanup.truncateAllTables(); }

    @Test
    @DisplayName("최초 잔액은 0이고 충전한 잔액을 API로 재조회할 수 있다")
    void chargesAndReads() throws Exception {
        mvc.perform(get("/api/v1/points").header("X-USER-ID", "1")).andExpect(jsonPath("$.data.balance").value(0));
        mvc.perform(post("/api/v1/points/charge").header("X-USER-ID", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(10000));
        mvc.perform(get("/api/v1/points").header("X-USER-ID", "1")).andExpect(jsonPath("$.data.balance").value(10000));
    }

    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {"{}", "{\"amount\":0}", "{\"amount\":-1}", "{\"amount\":1.5}",
        "{\"amount\":\"100\"}", "{\"amount\":9223372036854775808}", "{\"amount\":9223372036854775807}"})
    @DisplayName("잘못된 충전 입력과 합산 범위 초과는 400이며 기존 잔액을 유지한다")
    void rejectsInvalidCharge(String body) throws Exception {
        service.charge(1, 100);
        mvc.perform(post("/api/v1/points/charge").header("X-USER-ID", "1")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThat(service.balance(1)).isEqualTo(100);
    }

    @Test
    @DisplayName("동시 최초 충전에서도 잔액 행은 하나이고 모든 충전 금액이 반영된다")
    void serializesConcurrentCharges() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 8; i++) { tasks.add(pool.submit(() -> service.charge(1, 100))); }
            for (var task : tasks) { task.get(20, TimeUnit.SECONDS); }
        }
        assertThat(service.balance(1)).isEqualTo(800);
    }
}
