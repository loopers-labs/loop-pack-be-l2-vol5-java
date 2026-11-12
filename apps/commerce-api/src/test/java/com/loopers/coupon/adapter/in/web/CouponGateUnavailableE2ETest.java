package com.loopers.coupon.adapter.in.web;

import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponGatePort.GateUnavailableException;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.support.CouponTestFixtures;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 필수 실험 ⑤ Redis에 닿지 못하면 (과제 체크리스트: "Redis 장애에서 신규 발급을 우회하지 않고 기존 DB 결과 조회는 유지")
 * 등록·조회·발급은 503 + Retry-After이고 기록이 생기지 않는다. 내 쿠폰 조회는 DB라 200이다.
 * Redis 컨테이너는 테스트 JVM 전체가 함께 써서 멈출 수 없으므로, Redis 어댑터가 닿지 못했을 때 던지는 예외를 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CouponGateUnavailableE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponGatePort gate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Redis에 닿지 못하면 등록·조회·발급은 503이고 기록이 없다. 내 쿠폰 조회는 200이다.")
    @Test
    void redisDownFailsClosedButDbReadsWork() throws Exception {
        GateUnavailableException down = new GateUnavailableException("down", new RuntimeException("connection refused"));
        given(gate.register(any(), any(), anyInt(), anyInt())).willThrow(down);
        given(gate.status(any(), any(), anyInt())).willThrow(down);
        given(gate.issue(any(), any(), anyInt())).willThrow(down);
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, 10, null);
        Long me = userJpaRepository.save(new UserModel("고객")).getId();
        String base = "/api/v1/coupon-campaigns/" + campaign.getId();

        String retryAfter = mockMvc.perform(post(base + "/queue").header("X-USER-ID", me))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getHeader("Retry-After");
        mockMvc.perform(get(base + "/queue").header("X-USER-ID", me)).andExpect(status().isServiceUnavailable());
        mockMvc.perform(post(base + "/issues").header("X-USER-ID", me)).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/coupons").header("X-USER-ID", me)).andExpect(status().isOk());

        assertThat(retryAfter).isEqualTo("1");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox", Long.class)).isZero();
    }
}
