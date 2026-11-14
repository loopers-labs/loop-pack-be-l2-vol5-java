package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.infrastructure.coupon.RedisCouponIssuePermits;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "coupon.issue.capacity=3",
    "coupon.issue.admission.open-on-startup=false",
    "product.list-cache.warm-up.enabled=false",
    "payment.periodic-check.enabled=false",
    "order.confirmed-event.relay.enabled=false",
    "order.confirmed-event.outbox.metrics.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponIssueRedisScriptFailureE2ETest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponRepository coupons;
    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long campaignId;

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        if (campaignId != null) {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        }
    }

    @DisplayName("permit 키 타입이 Set이 아니면 500으로 거절하고 신규 수용을 닫는다.")
    @Test
    void closesAdmissionWhenPermitKeyHasWrongType() throws Exception {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-script-error-" + System.nanoTime(),
            1, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignId = campaign.getId();
        entityManager.clear();
        redisTemplate.opsForValue().set(RedisCouponIssuePermits.PERMITS_KEY, "not-a-set");
        admission.open();

        String body = mockMvc.perform(post("/api/v1/coupon-campaigns/%d/issues".formatted(campaignId))
                .header("X-USER-ID", 92001L))
            .andExpect(status().isInternalServerError())
            .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);

        assertThat(response.path("meta").path("errorCode").asText()).isEqualTo("Internal Server Error");
        assertThat(admission.isOpen()).isFalse();
        assertThat(coupons.countIssuedByCampaignId(campaignId)).isZero();
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
    }
}
