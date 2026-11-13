package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
import com.loopers.application.coupon.CouponPermitStoreUnavailableException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
class CouponIssueReleaseCommandFailureE2ETest {

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
    @MockitoSpyBean private CouponIssuePermits permitStore;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        redisTemplate.delete(RedisCouponIssuePermits.PERMITS_KEY);
        campaignIds.forEach(campaignId -> {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        });
    }

    @DisplayName("Redis permit 반환 명령이 실패해도 commit된 쿠폰을 응답하고 신규 수용을 닫는다.")
    @Test
    void preservesCommittedCouponWhenReleaseCommandFails() throws Exception {
        CouponCampaign campaign = prepareCampaign();
        long ownerId = 94001L;
        admission.open();
        doThrow(new CouponPermitStoreUnavailableException(new RuntimeException("simulated Redis command timeout")))
            .when(permitStore).release(anyString());

        JsonNode issued = data(mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", ownerId))
            .andExpect(status().isCreated()).andReturn());

        assertThat(coupons.findIssuedByCampaignIdAndUserId(campaign.getId(), ownerId)).isPresent();
        assertThat(coupons.countIssuedByCampaignId(campaign.getId())).isEqualTo(1);
        assertThat(campaigns.findById(campaign.getId()).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(admission.isOpen()).isFalse();
        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY)).hasSize(1);

        JsonNode repeated = data(mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", ownerId))
            .andExpect(status().isOk()).andReturn());
        assertThat(repeated.path("couponId").asLong()).isEqualTo(issued.path("couponId").asLong());
        verify(permitStore, times(1)).release(anyString());

        mockMvc.perform(post(issuePath(campaign.getId())).header("X-USER-ID", 94002L))
            .andExpect(status().isServiceUnavailable());
        assertThat(coupons.countIssuedByCampaignId(campaign.getId())).isEqualTo(1);
        assertThat(campaigns.findById(campaign.getId()).orElseThrow().getIssuedCount()).isEqualTo(1);
    }

    private CouponCampaign prepareCampaign() {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-release-command-failure-"
            + System.nanoTime(), 2, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignIds.add(campaign.getId());
        entityManager.clear();
        return campaign;
    }

    private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String issuePath(Long campaignId) {
        return "/api/v1/coupon-campaigns/%d/issues".formatted(campaignId);
    }
}
