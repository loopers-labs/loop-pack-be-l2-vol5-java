package com.loopers.interfaces.api.coupon;

import com.jayway.jsonpath.JsonPath;
import com.loopers.application.coupon.CampaignCache;
import com.loopers.application.coupon.CouponTransactions;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CouponV1ApiTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final MockMvc mvc;
    private final CouponTransactions couponTransactions;
    private final CampaignCache campaignCache;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponV1ApiTest(MockMvc mvc, CouponTransactions couponTransactions, CampaignCache campaignCache,
                    DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.mvc = mvc;
        this.couponTransactions = couponTransactions;
        this.campaignCache = campaignCache;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
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

        @DisplayName("CPN-004: 처음은 202 와 번호와 PENDING, 다시 요청하면 200 과 같은 번호다")
        @Test
        void acceptsThenReturnsSameNumber() throws Exception {
            long campaignId = openCampaign(5, true);

            issue(campaignId, 42L)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.issueNumber").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.couponId").doesNotExist());

            issue(campaignId, 42L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueNumber").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @DisplayName("CPN-004: DB 에 쿠폰이 생긴 뒤 다시 요청하면 200 과 ISSUED 와 couponId 다")
        @Test
        void returnsIssuedOnceRecorded() throws Exception {
            long campaignId = openCampaign(5, true);
            issue(campaignId, 42L).andExpect(status().isAccepted());
            Long couponId = couponTransactions.issue(campaignId, 42L, 1, NOW).id();

            issue(campaignId, 42L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueNumber").value(1))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.couponId").value(couponId))
                .andExpect(jsonPath("$.data.discountAmount").value(5000));
        }

        @DisplayName("CPN-005: 품절이면 409 COUPON_SOLD_OUT 이다")
        @Test
        void rejectsSoldOut() throws Exception {
            long campaignId = openCampaign(1, true);
            issue(campaignId, 1L).andExpect(status().isAccepted());

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

        @DisplayName("CPN-007: 접수만 된 쿠폰은 보유 목록에 없다")
        @Test
        void pendingIsNotListed() throws Exception {
            long campaignId = openCampaign(5, true);
            issue(campaignId, 42L).andExpect(status().isAccepted());

            mvc.perform(get("/api/v1/coupons").header("X-USER-ID", 42L))
                .andExpect(jsonPath("$.data", hasSize(0)));
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
        couponTransactions.issue(campaignId, 42L, 1, NOW);
        couponTransactions.issue(campaignId, 7L, 2, NOW);

        mvc.perform(get("/api/v1/coupons").header("X-USER-ID", 42L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].campaignId").value(campaignId));
    }
}
