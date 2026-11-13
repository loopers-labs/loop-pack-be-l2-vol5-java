package com.loopers.interfaces.api.coupon;

import com.jayway.jsonpath.JsonPath;
import com.loopers.application.coupon.CampaignCache;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CouponCampaignRecoveryAdminV1ApiTest {

    private final MockMvc mvc;
    private final CouponReservation couponReservation;
    private final CampaignCache campaignCache;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponCampaignRecoveryAdminV1ApiTest(MockMvc mvc, CouponReservation couponReservation, CampaignCache campaignCache,
                                         DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.mvc = mvc;
        this.couponReservation = couponReservation;
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

    private long openCampaign() throws Exception {
        String body = mvc.perform(post("/api-admin/v1/coupon-campaigns")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"블프 쿠폰","discountAmount":5000,"totalQuota":100,"active":true}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.campaignId")).longValue();
    }

    @DisplayName("FLT-008: 닫힌 캠페인을 관리자가 복구하면 DB 쿠폰으로 명단을 다시 만들어 열고, 200 과 함께 다시 올린 수를 준다. 그 뒤 접수는 202 다")
    @Test
    void rebuildsAndOpensAClosedCampaign() throws Exception {
        long campaignId = openCampaign();
        couponReservation.close(campaignId);

        mvc.perform(post("/api-admin/v1/coupon-campaigns/{campaignId}/recover", campaignId)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.campaignId").value(campaignId))
            .andExpect(jsonPath("$.data.restored").value(0));

        mvc.perform(post("/api/v1/coupon-campaigns/{campaignId}/issues", campaignId).header("X-USER-ID", 42L))
            .andExpect(status().isAccepted());
    }

    @DisplayName("FLT-008: 닫히지 않은 캠페인의 복구는 409 다. 복구는 재개이지 초기화가 아니다")
    @Test
    void rejectsRecoveryOfAnOpenCampaign() throws Exception {
        long campaignId = openCampaign();

        mvc.perform(post("/api-admin/v1/coupon-campaigns/{campaignId}/recover", campaignId)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/coupon-campaigns/{campaignId}/issues", campaignId).header("X-USER-ID", 42L))
            .andExpect(status().isAccepted());
    }

    @DisplayName("없는 캠페인의 복구는 404 다")
    @Test
    void rejectsUnknownCampaign() throws Exception {
        mvc.perform(post("/api-admin/v1/coupon-campaigns/{campaignId}/recover", 999_999L)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
    }

    @DisplayName("일반 사용자는 복구를 시작할 수 없다")
    @Test
    void rejectsCustomer() throws Exception {
        long campaignId = openCampaign();

        mvc.perform(post("/api-admin/v1/coupon-campaigns/{campaignId}/recover", campaignId)
                .with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
    }
}
