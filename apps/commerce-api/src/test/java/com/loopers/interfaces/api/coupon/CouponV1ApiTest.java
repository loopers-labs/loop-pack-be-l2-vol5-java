package com.loopers.interfaces.api.coupon;

import com.jayway.jsonpath.JsonPath;
import com.loopers.application.coupon.SoldOutCampaigns;
import com.loopers.config.redis.RedisConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CouponV1ApiTest {

    private final MockMvc mvc;
    private final RedisTemplate<String, String> redis;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final SoldOutCampaigns soldOutCampaigns;

    @Autowired
    CouponV1ApiTest(MockMvc mvc, @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                    DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp, SoldOutCampaigns soldOutCampaigns) {
        this.mvc = mvc;
        this.redis = redis;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.soldOutCampaigns = soldOutCampaigns;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        soldOutCampaigns.clear();
    }

    private ResultActions openCampaign(String body) throws Exception {
        return mvc.perform(post("/api-admin/v1/coupon-campaigns")
            .with(user("admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private long openCampaign(int quota, boolean active) throws Exception {
        String body = openCampaign("""
            {"name":"블프 쿠폰","discountAmount":5000,"totalQuota":%d,"active":%b}
            """.formatted(quota, active))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.campaignId")).longValue();
    }

    private ResultActions issue(long campaignId, long userId) throws Exception {
        return mvc.perform(post("/api/v1/coupon-campaigns/{campaignId}/issues", campaignId)
            .header("X-USER-ID", userId));
    }

    @Nested
    @DisplayName("CPN-002: 캠페인 생성")
    class Open {

        @DisplayName("관리자가 만들면 201 이고 발급 수는 0 이다")
        @Test
        void opens() throws Exception {
            openCampaign("""
                {"name":"블프 쿠폰","discountAmount":5000,"totalQuota":100,"active":true}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalQuota").value(100))
                .andExpect(jsonPath("$.data.issuedCount").value(0));
        }

        @DisplayName("한도가 0 이면 400 이다")
        @Test
        void rejectsZeroQuota() throws Exception {
            openCampaign("""
                {"name":"블프 쿠폰","discountAmount":5000,"totalQuota":0,"active":true}
                """)
                .andExpect(status().isBadRequest());
        }

        @DisplayName("일반 사용자는 만들 수 없다")
        @Test
        void rejectsCustomer() throws Exception {
            mvc.perform(post("/api-admin/v1/coupon-campaigns")
                    .with(user("customer").roles("USER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"블프 쿠폰","discountAmount":5000,"totalQuota":100,"active":true}
                        """))
                .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("쿠폰 발급")
    class Issue {

        @DisplayName("CPN-004: 처음은 201, 다시 요청하면 200 과 같은 couponId 다")
        @Test
        void createsThenReturnsExisting() throws Exception {
            long campaignId = openCampaign(5, true);

            String first = issue(campaignId, 42L)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
            Number couponId = JsonPath.read(first, "$.data.couponId");

            issue(campaignId, 42L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponId").value(couponId));
        }

        @DisplayName("CPN-005: 품절이면 409 COUPON_SOLD_OUT 이다")
        @Test
        void rejectsSoldOut() throws Exception {
            long campaignId = openCampaign(1, true);
            issue(campaignId, 1L).andExpect(status().isCreated());

            issue(campaignId, 2L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_SOLD_OUT"));
        }

        @DisplayName("CPN-005: 비활성이면 409 COUPON_CAMPAIGN_INACTIVE 다")
        @Test
        void rejectsInactive() throws Exception {
            long campaignId = openCampaign(5, false);

            issue(campaignId, 42L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_CAMPAIGN_INACTIVE"));
        }

        @DisplayName("ADM-005: 자리가 없으면 429 와 Retry-After 를 주고 쿠폰을 만들지 않는다")
        @Test
        void rejectsWhenNoSlot() throws Exception {
            long campaignId = openCampaign(5, true);
            redis.opsForSet().add("coupon:issue:admission:v1", "held-1", "held-2", "held-3");

            issue(campaignId, 42L)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_BUSY"));
            mvc.perform(get("/api/v1/coupons").header("X-USER-ID", 42L))
                .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @DisplayName("자리 없음 거절은 로그에 스택 트레이스를 남기지 않는다")
        @Test
        void logsRejectionWithoutStackTrace(CapturedOutput output) throws Exception {
            long campaignId = openCampaign(5, true);
            redis.opsForSet().add("coupon:issue:admission:v1", "held-1", "held-2", "held-3");

            issue(campaignId, 42L).andExpect(status().isTooManyRequests());

            assertThat(output.getAll()).contains("code=COUPON_BUSY");
            assertThat(output.getAll()).doesNotContain("com.loopers.support.error.CoreException:");
        }

        @DisplayName("CPN-006: 없는 캠페인은 404 다")
        @Test
        void rejectsUnknownCampaign() throws Exception {
            issue(999L, 42L).andExpect(status().isNotFound());
        }
    }

    @DisplayName("CPN-007: 보유 목록에는 본인 쿠폰만 있다")
    @Test
    void listsOwnCoupons() throws Exception {
        long campaignId = openCampaign(5, true);
        issue(campaignId, 42L).andExpect(status().isCreated());
        issue(campaignId, 7L).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", 42L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].campaignId").value(campaignId));
    }
}
