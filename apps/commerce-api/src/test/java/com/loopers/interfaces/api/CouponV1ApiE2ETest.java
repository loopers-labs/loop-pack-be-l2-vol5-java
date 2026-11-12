package com.loopers.interfaces.api;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponQueuePort;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CommerceApiApplication.class)
@AutoConfigureMockMvc
@Import(RedisTestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponV1ApiE2ETest {

    @Autowired MockMvc mvc;
    @Autowired CouponAdmissionGate admissionGate;
    @Autowired CouponQueuePort queuePort;
    @Autowired CouponCampaignJpaRepository campaignJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;
    @Autowired RedisCleanUp redisCleanUp;

    private Long campaignId;

    @BeforeEach
    void setUp() {
        CouponCampaignModel campaign = campaignJpaRepository.save(
            new CouponCampaignModel("API 테스트 쿠폰", 10, ZonedDateTime.now(), ZonedDateTime.now().plusDays(1))
        );
        campaignId = campaign.getId();
    }

    @AfterEach
    void tearDown() {
        admissionGate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{id}/queue - 게이트 열림 시 대기열 진입 성공")
    @Test
    void enqueue_gateOpen_returns200() throws Exception {
        admissionGate.open(campaignId);

        mvc.perform(post("/api/v1/coupon-campaigns/{id}/queue", campaignId)
                .header("X-USER-ID", 1L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.campaignId").value(campaignId));
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{id}/queue - 게이트 닫힘 시 503")
    @Test
    void enqueue_gateClosed_returns503() throws Exception {
        mvc.perform(post("/api/v1/coupon-campaigns/{id}/queue", campaignId)
                .header("X-USER-ID", 1L))
            .andExpect(status().isServiceUnavailable());
    }

    @DisplayName("GET /api/v1/coupon-campaigns/{id}/queue/me - 대기열 상태 조회")
    @Test
    void getQueueStatus_returns200() throws Exception {
        admissionGate.open(campaignId);
        queuePort.enqueue(campaignId, 1L, 10);

        mvc.perform(get("/api/v1/coupon-campaigns/{id}/queue/me", campaignId)
                .header("X-USER-ID", 1L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{id}/issues - admitted 아닌 사용자는 400")
    @Test
    void issue_notAdmitted_returns400() throws Exception {
        admissionGate.open(campaignId);

        mvc.perform(post("/api/v1/coupon-campaigns/{id}/issues", campaignId)
                .header("X-USER-ID", 1L))
            .andExpect(status().isBadRequest());
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{id}/issues - 대기열 꽉 참 시 429")
    @Test
    void enqueue_queueFull_returns429() throws Exception {
        admissionGate.open(campaignId);
        for (long i = 1; i <= 10; i++) {
            queuePort.enqueue(campaignId, i, 10);
        }

        mvc.perform(post("/api/v1/coupon-campaigns/{id}/queue", campaignId)
                .header("X-USER-ID", 99L))
            .andExpect(status().isTooManyRequests());
    }
}
