package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponAdmission;
import com.loopers.application.coupon.CouponCampaignSetup;
import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.infrastructure.coupon.AdmissionRedisConfig;
import com.loopers.infrastructure.coupon.RedisAdmissionPermits;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 쿠폰 발급·보유 목록 API(docs/week8/decisions.md D-9, D-15, D-16, INV-802, INV-806). 수용은 준비 단계에서 연다(D-7, D-11).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponV1ApiE2ETest {

    private static final String MY_COUPONS = "/api/v1/coupons";
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponListResponse>> COUPON_LIST =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CouponCampaignSetup couponCampaignSetup;

    @Autowired
    private CouponAdmission couponAdmission;

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @Autowired
    @Qualifier(AdmissionRedisConfig.ADMISSION_REDIS_TEMPLATE)
    private RedisTemplate<String, String> admissionRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        couponAdmission.open();
    }

    @AfterEach
    void tearDown() {
        couponAdmission.open();
        databaseCleanUp.truncateAllTables();
    }

    private static String issuePath(Long campaignId) {
        return "/api/v1/coupon-campaigns/" + campaignId + "/issues";
    }

    private static HttpEntity<Void> asUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long campaignId, Long userId) {
        return testRestTemplate.exchange(issuePath(campaignId), HttpMethod.POST, asUser(userId), COUPON);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> myCoupons(Long userId) {
        return testRestTemplate.exchange(MY_COUPONS, HttpMethod.GET, asUser(userId), COUPON_LIST);
    }

    private Long campaign(int totalQuota, String benefit, long discountAmount) {
        return couponCampaignSetup.create("오픈 기념", totalQuota, benefit, discountAmount).campaignId();
    }

    private Long tokenCount() {
        return admissionRedisTemplate.opsForSet().size(RedisAdmissionPermits.PERMITS_KEY);
    }

    private int issuedCount(Long campaignId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
        return value == null ? -1 : value;
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{campaignId}/issues")
    @Nested
    class Issue {
        @DisplayName("처음이면 201, 다시 요청하면 200과 같은 couponId이고, 발급 수는 1이며 자리는 모두 반환된다.")
        @Test
        void created_thenOkWithSameCoupon() {
            Long campaignId = campaign(10, "5,000원 할인권", 5_000L);

            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> first = issue(campaignId, 7L);
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> second = issue(campaignId, 7L);

            assertAll(
                () -> assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(first.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
                () -> assertThat(first.getBody().data().campaignId()).isEqualTo(campaignId),
                () -> assertThat(first.getBody().data().benefitDescription()).isEqualTo("5,000원 할인권"),
                () -> assertThat(first.getBody().data().discountAmount()).isEqualTo(5_000L),
                () -> assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(second.getBody().data().couponId()).isEqualTo(first.getBody().data().couponId()),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(tokenCount()).isZero()
            );
        }

        @DisplayName("없는 캠페인이면 404, data는 null이다.")
        @Test
        void notFound() {
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = issue(999_999L, 7L);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                () -> assertThat(response.getBody().data()).isNull(),
                () -> assertThat(tokenCount()).isZero()
            );
        }

        @DisplayName("품절이거나 비활성이면 409다.")
        @Test
        void soldOutOrInactive_conflict() {
            Long soldOut = campaign(1, "5,000원 할인권", 5_000L);
            Long inactive = campaign(10, "5,000원 할인권", 5_000L);
            couponCampaignSetup.deactivate(inactive);
            issue(soldOut, 1L);

            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> soldOutResponse = issue(soldOut, 2L);
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> inactiveResponse = issue(inactive, 2L);

            assertAll(
                () -> assertThat(soldOutResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(soldOutResponse.getBody().data()).isNull(),
                () -> assertThat(inactiveResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(issuedCount(soldOut)).isEqualTo(1),
                () -> assertThat(issuedCount(inactive)).isZero(),
                () -> assertThat(tokenCount()).isZero()
            );
        }

        @DisplayName("수용이 닫혀 있으면 신규 발급은 503이고, 이미 받은 사용자는 200과 같은 couponId를 받는다.")
        @Test
        void closed_unavailableForNew_okForExisting() {
            Long campaignId = campaign(10, "5,000원 할인권", 5_000L);
            Long couponId = issue(campaignId, 7L).getBody().data().couponId();
            couponAdmission.close();

            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> newcomer = issue(campaignId, 8L);
            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> existing = issue(campaignId, 7L);

            assertAll(
                () -> assertThat(newcomer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(newcomer.getBody().data()).isNull(),
                () -> assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(existing.getBody().data().couponId()).isEqualTo(couponId),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1)
            );
        }

        @DisplayName("발급이 commit된 뒤 응답을 잃은 사용자가 다시 요청하면, 200과 같은 couponId를 받고 자리는 남지 않는다.")
        @Test
        void responseLost_retryReturnsSameCoupon() {
            Long campaignId = campaign(10, "5,000원 할인권", 5_000L);
            // 응답을 받지 못한 첫 실행 — 서버 쪽에서는 commit과 자리 반환까지 끝났다
            CouponIssueResult lost = couponIssueFacade.issue(campaignId, 7L);

            ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> retry = issue(campaignId, 7L);

            assertAll(
                () -> assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(retry.getBody().data().couponId()).isEqualTo(lost.coupon().couponId()),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(tokenCount()).isZero()
            );
        }
    }

    @DisplayName("GET /api/v1/coupons")
    @Nested
    class MyCoupons {
        @DisplayName("요청자 것만 발급 시각 내림차순으로 보이고, 혜택은 캠페인이 바뀌어도 발급 순간의 값이다.")
        @Test
        void onlyOwnCoupons_latestFirst_withSnapshotBenefit() {
            Long older = campaign(10, "5,000원 할인권", 5_000L);
            Long newer = campaign(10, "1,000원 할인권", 1_000L);
            Long olderCouponId = issue(older, 7L).getBody().data().couponId();
            Long newerCouponId = issue(newer, 7L).getBody().data().couponId();
            Long othersCouponId = issue(older, 8L).getBody().data().couponId();
            couponCampaignSetup.changeBenefit(older, "3,000원 할인권", 3_000L);

            ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> response = myCoupons(7L);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId)
                    .containsExactly(newerCouponId, olderCouponId)
                    .doesNotContain(othersCouponId),
                () -> assertThat(response.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::benefitDescription)
                    .containsExactly("1,000원 할인권", "5,000원 할인권"),
                () -> assertThat(response.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::discountAmount)
                    .containsExactly(1_000L, 5_000L)
            );
        }

        @DisplayName("쿠폰이 없으면 빈 목록이다.")
        @Test
        void emptyWhenNone() {
            ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> response = myCoupons(7L);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().coupons()).isEmpty()
            );
        }
    }
}
