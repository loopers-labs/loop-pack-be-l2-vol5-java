package com.loopers.interfaces.api.admin.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionGate.CloseReason;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 8주차 설계 §7-3·§8-1 관리자 API. 기존 /api-admin/** 경계(ROLE_ADMIN + CSRF)가 그대로 적용되는지와 open·close·상태 조회의 응답을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminCouponAdmissionV1ApiE2ETest {

    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));

    private final MockMvc mockMvc;
    private final CouponAdmissionGate gate;
    private final CouponAdmissionQueue queue;
    private final CouponCampaignRepository couponCampaignRepository;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    private Long campaignId;

    @Autowired
    AdminCouponAdmissionV1ApiE2ETest(
        MockMvc mockMvc,
        CouponAdmissionGate gate,
        CouponAdmissionQueue queue,
        CouponCampaignRepository couponCampaignRepository,
        UserJpaRepository userJpaRepository,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.mockMvc = mockMvc;
        this.gate = gate;
        this.queue = queue;
        this.couponCampaignRepository = couponCampaignRepository;
        this.userJpaRepository = userJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        campaignId = couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", 10, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT)).getId();
    }

    @AfterEach
    void tearDown() {
        gate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private String path(String suffix) {
        return "/api-admin/v1/coupon-campaigns/" + campaignId + "/admission" + suffix;
    }

    private ResultActions adminPost(String url) throws Exception {
        return mockMvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf()));
    }

    private ResultActions adminGet(String url) throws Exception {
        return mockMvc.perform(get(url).with(user("admin").roles("ADMIN")));
    }

    @DisplayName("open / close")
    @Nested
    class OpenClose {
        @DisplayName("관리자가 open 하면 200 이고 gate 가 열려 세대값이 생긴다. close 하면 닫히고 이유는 ADMIN 이다.")
        @Test
        void opensAndCloses() throws Exception {
            adminPost(path("/open")).andExpect(status().isOk()).andExpect(jsonPath("$.meta.result").value("SUCCESS"));
            assertThat(gate.isOpen(campaignId)).isTrue();
            assertThat(gate.generation(campaignId)).isPresent();

            adminPost(path("/close")).andExpect(status().isOk());
            assertThat(gate.isOpen(campaignId)).isFalse();
            assertThat(gate.closure(campaignId).orElseThrow().reason()).isEqualTo(CloseReason.ADMIN);
        }

        @DisplayName("없는 캠페인은 404 이고 gate 상태가 생기지 않는다.")
        @Test
        void notFound() throws Exception {
            adminPost("/api-admin/v1/coupon-campaigns/999999/admission/open")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
            assertThat(gate.isOpen(999_999L)).isFalse();
        }

        @DisplayName("ROLE_ADMIN 이 아니거나 CSRF 토큰이 없으면 403 이고 gate 는 닫힌 채다. X-USER-ID 헤더는 관리자 신원이 아니다.")
        @Test
        void rejectsWithoutAdminBoundary() throws Exception {
            mockMvc.perform(post(path("/open")).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post(path("/open")).with(user("user").roles("USER")).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post(path("/open")).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
            mockMvc.perform(post(path("/open")).header("X-USER-ID", "1").with(csrf())).andExpect(status().isForbidden());

            assertThat(gate.isOpen(campaignId)).isFalse();
        }
    }

    @DisplayName("GET 상태 조회")
    @Nested
    class StatusQuery {
        @DisplayName("닫힌 gate 는 open=false, closeReason 와 함께 Redis 값(대기·승격·자리·접수 번호)과 서킷 상태를 돌려준다.")
        @Test
        void showsClosedGateAndCounts() throws Exception {
            String gen = gate.open(campaignId);
            for (int i = 0; i < 5; i++) {
                queue.enqueue(campaignId, userJpaRepository.save(new User()).getId(), 100L, gen);
            }
            queue.promote(campaignId, gen);
            gate.forceClose(campaignId, CloseReason.REDIS_ERROR);

            adminGet(path(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open").value(false))
                .andExpect(jsonPath("$.data.closeReason").value("REDIS_ERROR"))
                .andExpect(jsonPath("$.data.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.generationMatches").value(true))
                .andExpect(jsonPath("$.data.dbCircuitState").value("CLOSED"))
                .andExpect(jsonPath("$.data.waiting").value(2))
                .andExpect(jsonPath("$.data.admitted").value(3))
                .andExpect(jsonPath("$.data.permits").value(0))
                .andExpect(jsonPath("$.data.seq").value(5))
                .andExpect(jsonPath("$.data.activeRequests").value(0))
                .andExpect(jsonPath("$.data.autoReopenEnabled").value(false))
                .andExpect(jsonPath("$.data.recentAutoReopens").value(0))
                .andExpect(jsonPath("$.data.redisUnavailable").value(false));

            assertThat(gate.isOpen(campaignId)).as("조회는 gate 상태를 바꾸지 않는다").isFalse();
        }

        @DisplayName("Redis 가 비어 세대가 사라지면 generationMatches=false 로 알리고, 열린 gate 는 closeReason 이 응답에 없다.")
        @Test
        void showsGenerationMismatch() throws Exception {
            gate.open(campaignId);
            redisCleanUp.truncateAll();

            adminGet(path(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open").value(true))
                .andExpect(jsonPath("$.data.closeReason").doesNotExist())
                .andExpect(jsonPath("$.data.generationMatches").value(false))
                .andExpect(jsonPath("$.data.redisUnavailable").value(false));

            assertThat(gate.isOpen(campaignId)).as("읽기는 세대 불일치를 봐도 gate 를 닫지 않는다").isTrue();
        }

        @DisplayName("상태 조회도 ROLE_ADMIN 이 아니면 403 이다.")
        @Test
        void rejectsNonAdmin() throws Exception {
            mockMvc.perform(get(path(""))).andExpect(status().isForbidden());
            mockMvc.perform(get(path("")).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        }
    }
}
