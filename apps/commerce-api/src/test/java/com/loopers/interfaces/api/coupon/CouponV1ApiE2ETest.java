package com.loopers.interfaces.api.coupon;

import com.loopers.domain.coupon.entity.Coupon;
import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.entity.CouponIssueRequest;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.user.entity.User;
import com.loopers.infrastructure.coupon.persistence.CouponIssueRequestJpaRepository;
import com.loopers.infrastructure.coupon.persistence.CouponJpaRepository;
import com.loopers.infrastructure.user.persistence.UserJpaRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.ZonedDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CouponV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-13T10:00:00+09:00");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private CouponCampaignRepository couponCampaignRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private CouponIssueRequestJpaRepository couponIssueRequestJpaRepository;

    @Autowired
    private CouponIssueQueue couponIssueQueue;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private User user;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private CouponCampaign campaign(boolean open) {
        CouponCampaign campaign = new CouponCampaign("행사", 1, DiscountType.FIXED_AMOUNT, 5_000L, "5,000원 할인권");
        if (open) {
            campaign.open();
        }
        return couponCampaignRepository.save(campaign);
    }

    /** 한도 1, 예비 범위 2 로 연 캠페인 */
    private Long openCampaign() {
        CouponCampaign campaign = campaign(true);
        couponIssueQueue.open(campaign.getId(), 1, 2, "1");
        return campaign.getId();
    }

    private MockHttpServletRequestBuilder as(Long userId, MockHttpServletRequestBuilder request) {
        return request.header(USER_ID_HEADER, String.valueOf(userId));
    }

    private static String issuesOf(Long campaignId) {
        return "/api/v1/coupon-campaigns/" + campaignId + "/issues";
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{campaignId}/issues")
    @Nested
    class RequestIssue {
        @DisplayName("접수되면 202 · WAITING 이고 Retry-After 1초를 안내한다. 다시 요청해도 같다. (D-117, D-144, D-148)")
        @Test
        void acceptsWaiting() throws Exception {
            Long campaignId = openCampaign();

            for (int i = 0; i < 2; i++) {
                mvc.perform(as(user.getId(), post(issuesOf(campaignId))))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(jsonPath("$.data.status").value("WAITING"))
                    .andExpect(jsonPath("$.data.campaignId").value(campaignId));
            }
        }

        @DisplayName("예비면 202 · RESERVE · 현재 예비 위치, 그 밖이면 200 · SOLD_OUT 이다. (D-121, D-144, D-149)")
        @Test
        void reservesThenSoldOut() throws Exception {
            // arrange
            Long campaignId = openCampaign();
            User second = userJpaRepository.save(new User());
            User third = userJpaRepository.save(new User());
            mvc.perform(as(user.getId(), post(issuesOf(campaignId))));

            // act & assert
            mvc.perform(as(second.getId(), post(issuesOf(campaignId))))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.data.status").value("RESERVE"))
                .andExpect(jsonPath("$.data.reservePosition").value(1));
            mvc.perform(as(third.getId(), post(issuesOf(campaignId))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.data.status").value("SOLD_OUT"));
        }

        @DisplayName("없는 캠페인은 404, 열지 않은 캠페인은 409 다. (D-144)")
        @Test
        void notFoundAndNotActive() throws Exception {
            Long ready = campaign(false).getId();

            mvc.perform(as(user.getId(), post(issuesOf(999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("CAMPAIGN_NOT_FOUND"));
            mvc.perform(as(user.getId(), post(issuesOf(ready))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("CAMPAIGN_NOT_ACTIVE"));
        }

        @DisplayName("열렸는데 Redis 표식을 잃었으면 503 이고 Retry-After 10초를 안내한다. (D-139, D-148)")
        @Test
        void unavailable_whenMarkerLost() throws Exception {
            Long campaignId = campaign(true).getId();

            mvc.perform(as(user.getId(), post(issuesOf(campaignId))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ISSUE_UNAVAILABLE"));
        }

        @DisplayName("요청자를 확인할 수 없으면 401 이다. 다른 사용자 대신 접수할 수 없다.")
        @Test
        void unauthenticated_withoutUser() throws Exception {
            Long campaignId = openCampaign();

            mvc.perform(post(issuesOf(campaignId)))
                .andExpect(status().isUnauthorized());
        }
    }

    @DisplayName("GET /api/v1/coupon-campaigns/{campaignId}/issues/me")
    @Nested
    class GetMyIssue {
        @DisplayName("접수 기록이 없으면 404 다. (D-145)")
        @Test
        void notFound_withoutRecord() throws Exception {
            Long campaignId = openCampaign();

            mvc.perform(as(user.getId(), get(issuesOf(campaignId) + "/me")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ISSUE_NOT_FOUND"));
        }

        @DisplayName("접수했으면 POST 와 같은 본문을 200 으로 돌려준다. (D-145)")
        @Test
        void returnsSameBody_afterRequest() throws Exception {
            Long campaignId = openCampaign();
            mvc.perform(as(user.getId(), post(issuesOf(campaignId))));

            mvc.perform(as(user.getId(), get(issuesOf(campaignId) + "/me")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.data.status").value("WAITING"));
        }

        @DisplayName("Redis 를 쓸 수 없으면 DB 접수 기록으로 대신 읽고, 기록이 없으면 503 이다. (D-145, D-146)")
        @Test
        void readsFromDatabase_whenRedisLost() throws Exception {
            // arrange: 열린 캠페인인데 Redis 표식이 없다
            Long campaignId = campaign(true).getId();
            User other = userJpaRepository.save(new User());
            couponIssueRequestJpaRepository.save(CouponIssueRequest.accepted(campaignId, user.getId(), 1, 1));

            // act & assert
            mvc.perform(as(user.getId(), get(issuesOf(campaignId) + "/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"));
            mvc.perform(as(other.getId(), get(issuesOf(campaignId) + "/me")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.errorCode").value("COUPON_ISSUE_UNAVAILABLE"));
        }
    }

    @DisplayName("GET /api/v1/coupons")
    @Nested
    class GetMyCoupons {
        @DisplayName("본인 쿠폰만, 발급 시각 → couponId 내림차순으로 혜택과 함께 돌려준다. (D-147, D-150)")
        @Test
        void returnsOwnCouponsInOrder() throws Exception {
            // arrange
            CouponCampaign first = campaign(true);
            CouponCampaign second = campaign(true);
            User other = userJpaRepository.save(new User());
            couponJpaRepository.save(Coupon.issue(first, user.getId(), 1, NOW));
            Coupon latest = couponJpaRepository.save(Coupon.issue(second, user.getId(), 1, NOW.plusMinutes(1)));
            CouponCampaign third = campaign(true);
            couponJpaRepository.save(Coupon.issue(third, other.getId(), 1, NOW.plusMinutes(2)));

            // act & assert
            mvc.perform(as(user.getId(), get("/api/v1/coupons")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[0].couponId").value(latest.getId()))
                .andExpect(jsonPath("$.data.items[0].description").value("5,000원 할인권"))
                .andExpect(jsonPath("$.data.items[1].campaignId").value(first.getId()));
        }
    }
}
