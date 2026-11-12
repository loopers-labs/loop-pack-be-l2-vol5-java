package com.loopers.interfaces.api;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CommerceApiApplication.class)
@AutoConfigureMockMvc
@Import(RedisTestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponAdminV1ApiE2ETest {

    @Autowired MockMvc mvc;
    @Autowired CouponAdmissionGate admissionGate;
    @Autowired CouponCampaignJpaRepository campaignJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;
    @Autowired RedisCleanUp redisCleanUp;

    private Long campaignId;

    @BeforeEach
    void setUp() {
        CouponCampaignModel campaign = campaignJpaRepository.save(
            new CouponCampaignModel("어드민 API 테스트", 100, ZonedDateTime.now(), ZonedDateTime.now().plusDays(1))
        );
        campaignId = campaign.getId();
    }

    @AfterEach
    void tearDown() {
        admissionGate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("POST /api-admin/v1/coupon-campaigns - 캠페인 생성")
    @Test
    void createCampaign_returns200() throws Exception {
        mvc.perform(post("/api-admin/v1/coupon-campaigns")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "신규 쿠폰",
                        "totalQuota": 50,
                        "startAt": "2026-11-01T00:00:00+09:00",
                        "endAt": "2026-11-30T23:59:59+09:00"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("신규 쿠폰"))
            .andExpect(jsonPath("$.data.totalQuota").value(50));
    }

    @DisplayName("POST /api-admin/v1/coupon-campaigns/{id}/admission/open - 게이트 오픈")
    @Test
    void openAdmission_returns200() throws Exception {
        mvc.perform(post("/api-admin/v1/coupon-campaigns/{id}/admission/open", campaignId)
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isOpen").value(true));
    }

    @DisplayName("POST /api-admin/v1/coupon-campaigns/{id}/admission/close - 게이트 클로즈")
    @Test
    void closeAdmission_returns200() throws Exception {
        admissionGate.open(campaignId);

        mvc.perform(post("/api-admin/v1/coupon-campaigns/{id}/admission/close", campaignId)
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isOpen").value(false));
    }

    @DisplayName("GET /api-admin/v1/coupon-campaigns/{id}/admission - 게이트 상태 조회")
    @Test
    void getAdmission_returns200() throws Exception {
        mvc.perform(get("/api-admin/v1/coupon-campaigns/{id}/admission", campaignId)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.campaignId").value(campaignId))
            .andExpect(jsonPath("$.data.isOpen").value(false));
    }

    @DisplayName("어드민 인증 없이 접근하면 403")
    @Test
    void adminEndpoint_noAuth_returns403() throws Exception {
        mvc.perform(post("/api-admin/v1/coupon-campaigns/{id}/admission/open", campaignId))
            .andExpect(status().isForbidden());
    }
}
