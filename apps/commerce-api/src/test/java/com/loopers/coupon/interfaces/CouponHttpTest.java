package com.loopers.coupon.interfaces;

import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.support.fixture.CouponTestFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.ids;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class CouponHttpTest {

    private static final String ISSUE = "/api/v1/coupon-campaigns/{campaignId}/issues";
    private static final String MY_COUPONS = "/api/v1/coupons";
    private static final long MISSING_CAMPAIGN_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CommerceFixture commerce;
    private CouponTestFixture coupons;

    @BeforeEach
    void setUp() {
        commerce = new CommerceFixture(entityManager, transactionManager);
        coupons = new CouponTestFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        commerce.truncateRemainingTables();
    }

    @DisplayName("[INV-COUPON-54] 이미 발급된 사용자의 재요청은 기존 쿠폰을 반환하고 캠페인 발급 수를 늘리지 않는다.")
    @Nested
    class RepeatedIssueReturnsExistingCoupon {

        @DisplayName("[상태 전이] 첫 요청은 201, 재요청은 같은 쿠폰 ID로 200을 반환한다.")
        @Test
        void returnsSameCouponForRepeatedRequest() throws Exception {
            // arrange
            User user = commerce.user();
            CouponCampaign campaign = coupons.campaign("출시 캠페인", "5,000원 할인권", true, 10);

            // act
            MvcResult first = issueCoupon(user, campaign, HttpStatus.CREATED);
            MvcResult repeated = issueCoupon(user, campaign, HttpStatus.OK);

            // assert
            long couponId = data(first).path("id").asLong();
            assertThat(couponId).isPositive();
            assertThat(data(repeated).path("id").asLong()).isEqualTo(couponId);
            assertThat(data(first).path("userId").asLong()).isEqualTo(user.getId());
            assertThat(data(first).path("campaignId").asLong()).isEqualTo(campaign.getId());
            assertThat(data(first).path("benefit").asText()).isEqualTo("5,000원 할인권");
            assertThat(data(first).path("issuedAt").asText()).isNotBlank();
            assertThat(coupons.issuedCount(campaign.getId())).isEqualTo(1L);
            assertThat(coupons.couponCount(campaign.getId())).isEqualTo(1L);
        }
    }

    @DisplayName("[INV-COUPON-55] 캠페인이 품절·비활성화되어도 기존 쿠폰은 해당 사용자의 보유 목록에서 조회되며 발급 기록으로 확인된다.")
    @Nested
    class ExistingCouponRemainsVisibleWhenCampaignUnavailable {

        @DisplayName("[상태 전이] 캠페인 한도가 소진된 뒤에도 기존 쿠폰을 목록에서 조회할 수 있다.")
        @Test
        void readsExistingCouponAfterCampaignSellsOut() throws Exception {
            // arrange
            User user = commerce.user();
            CouponCampaign campaign = coupons.campaign("한정 캠페인", "5,000원 할인권", true, 1);
            issueCoupon(user, campaign, HttpStatus.CREATED);
            long couponId = coupons.couponId(campaign.getId(), user.getId());

            // act & assert
            MvcResult result = mockMvc.perform(get(MY_COUPONS).with(customer(user)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(couponId))
                .andExpect(jsonPath("$.data[0].campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.data[0].benefit").value("5,000원 할인권"))
                .andReturn();
            assertThat(ids(data(result))).containsExactly(couponId);
        }

        @DisplayName("[상태 전이] 캠페인이 비활성화된 뒤에도 기존 쿠폰을 목록에서 조회할 수 있다.")
        @Test
        void readsExistingCouponAfterCampaignIsDeactivated() throws Exception {
            // arrange
            User user = commerce.user();
            CouponCampaign campaign = coupons.campaign("중단 캠페인", "10% 할인권", true, 10);
            issueCoupon(user, campaign, HttpStatus.CREATED);
            long couponId = coupons.couponId(campaign.getId(), user.getId());
            coupons.stopIssuance(campaign.getId());

            // act & assert
            MvcResult result = mockMvc.perform(get(MY_COUPONS).with(customer(user)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(couponId))
                .andExpect(jsonPath("$.data[0].benefit").value("10% 할인권"))
                .andReturn();
            assertThat(ids(data(result))).containsExactly(couponId);
        }
    }

    @DisplayName("[API 계약] 캠페인 발급 요청의 오류는 계약된 상태와 오류 코드로 응답한다.")
    @Nested
    class CampaignIssueErrors {

        @DisplayName("[동등 클래스 분할] 존재하지 않는 캠페인은 404 COUPON_CAMPAIGN_NOT_FOUND이다.")
        @Test
        void rejectsMissingCampaign() throws Exception {
            User user = commerce.user();

            mockMvc.perform(post(ISSUE, MISSING_CAMPAIGN_ID).with(customer(user)).with(csrf()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "COUPON_CAMPAIGN_NOT_FOUND"));
        }

        @DisplayName("[동등 클래스 분할] 비활성 캠페인은 409 COUPON_CAMPAIGN_INACTIVE이다.")
        @Test
        void rejectsInactiveCampaign() throws Exception {
            User user = commerce.user();
            CouponCampaign campaign = coupons.campaign("중단 캠페인", "10% 할인권", false, 10);

            mockMvc.perform(post(ISSUE, campaign.getId()).with(customer(user)).with(csrf()))
                .andExpect(failure(HttpStatus.CONFLICT, "COUPON_CAMPAIGN_INACTIVE"));
        }

        @DisplayName("[경계값 분석] 한도가 0인 캠페인은 409 COUPON_CAMPAIGN_SOLD_OUT이다.")
        @Test
        void rejectsCampaignWithoutRemainingQuota() throws Exception {
            User user = commerce.user();
            CouponCampaign campaign = coupons.campaign("소진 캠페인", "5,000원 할인권", true, 0);

            mockMvc.perform(post(ISSUE, campaign.getId()).with(customer(user)).with(csrf()))
                .andExpect(failure(HttpStatus.CONFLICT, "COUPON_CAMPAIGN_SOLD_OUT"));
        }
    }

    @DisplayName("[API 계약] 쿠폰 목록은 요청자를 식별할 수 없으면 거절한다.")
    @Nested
    class CouponRequesterIdentity {

        @DisplayName("[동등 클래스 분할] X-USER-ID가 없으면 401 USER_NOT_IDENTIFIED이다.")
        @Test
        void rejectsMissingRequester() throws Exception {
            mockMvc.perform(get(MY_COUPONS))
                .andExpect(failure(HttpStatus.UNAUTHORIZED, "USER_NOT_IDENTIFIED"));
        }
    }

    private MvcResult issueCoupon(User user, CouponCampaign campaign, HttpStatus status) throws Exception {
        return mockMvc.perform(post(ISSUE, campaign.getId()).with(customer(user)).with(csrf()))
            .andExpect(success(status))
            .andReturn();
    }
}
