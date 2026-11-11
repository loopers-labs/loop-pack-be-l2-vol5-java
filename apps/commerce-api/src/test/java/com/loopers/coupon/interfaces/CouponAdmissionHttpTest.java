package com.loopers.coupon.interfaces;

import com.loopers.config.redis.RedisConfig;
import com.loopers.coupon.application.CouponAdmission;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.infrastructure.CouponAdmissionProperties;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.support.fixture.CouponTestFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import({MySqlTestContainersConfig.class, RedisTestContainersConfig.class})
class CouponAdmissionHttpTest {

    private static final String ISSUE = "/api/v1/coupon-campaigns/{campaignId}/issues";
    private static final String PERMITS_KEY = "coupon:issue:permits";
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CouponAdmission admission;

    @Autowired
    private CouponAdmissionProperties admissionProperties;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redis;

    private CommerceFixture commerce;
    private CouponTestFixture coupons;

    @BeforeEach
    void setUp() {
        redis.delete(PERMITS_KEY);
        commerce = new CommerceFixture(entityManager, transactionManager);
        coupons = new CouponTestFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        redis.delete(PERMITS_KEY);
        commerce.truncateRemainingTables();
    }

    @DisplayName("[수용량 경계] Redis 자리가 모두 점유되면 429로 거절하고 DB 발급은 시작하지 않는다.")
    @Test
    void rejectsIssueWhenAllRedisPermitsAreOccupied() throws Exception {
        // arrange
        User user = commerce.user();
        CouponCampaign campaign = coupons.campaign("수용량 검증 캠페인", "5,000원 할인권", true, 10);
        List<String> occupiedTokens = new ArrayList<>();
        for (int i = 0; i < admissionProperties.capacity(); i++) {
            occupiedTokens.add(admission.acquire().orElseThrow());
        }

        // act & assert
        mockMvc.perform(post(ISSUE, campaign.getId()).with(customer(user)).with(csrf()))
            .andExpect(failure(HttpStatus.TOO_MANY_REQUESTS, "COUPON_ADMISSION_FULL"));

        assertThat(coupons.issuedCount(campaign.getId())).isZero();
        assertThat(coupons.couponCount(campaign.getId())).isZero();

        // release one held permit and verify the next request can issue
        admission.release(occupiedTokens.getFirst());
        mockMvc.perform(post(ISSUE, campaign.getId()).with(customer(user)).with(csrf()))
            .andExpect(success(HttpStatus.CREATED))
            .andExpect(jsonPath("$.data.campaignId").value(campaign.getId()));
        assertThat(coupons.issuedCount(campaign.getId())).isEqualTo(1L);
        assertThat(coupons.couponCount(campaign.getId())).isEqualTo(1L);
    }
}
