package com.loopers.interfaces.api.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8주차 설계 §8-1·§8-2 의 HTTP 계약. 응답 코드·봉투·errorCode 와 "타인의 상태·보유 목록이 응답에 나타나지 않는다"를 실제 API 로 확인한다.
 * gate 는 기본 닫힘이라 @BeforeEach 에서 연다 (C12). 승격은 스케줄러 대신 promote 를 직접 호출한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));
    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> MAP_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<List<CouponV1Dto.CouponResponse>>> LIST_TYPE = new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final CouponAdmissionGate gate;
    private final CouponAdmissionQueue queue;
    private final CouponCampaignRepository couponCampaignRepository;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    private Long campaignId;
    private String gen;

    @Autowired
    CouponV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        CouponAdmissionGate gate,
        CouponAdmissionQueue queue,
        CouponCampaignRepository couponCampaignRepository,
        UserJpaRepository userJpaRepository,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.gate = gate;
        this.queue = queue;
        this.couponCampaignRepository = couponCampaignRepository;
        this.userJpaRepository = userJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        campaignId = couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", 100, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT)).getId();
        gen = gate.open(campaignId);
    }

    @AfterEach
    void tearDown() {
        gate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return headers;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> post(String path, Long userId) {
        return testRestTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers(userId)), MAP_TYPE);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> get(String path, Long userId) {
        return testRestTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers(userId)), MAP_TYPE);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> apply(Long userId) {
        return post("/api/v1/coupon-campaigns/" + campaignId + "/issues", userId);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> status(Long userId) {
        return get("/api/v1/coupon-campaigns/" + campaignId + "/issues/me", userId);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> claim(Long userId) {
        return post("/api/v1/coupon-campaigns/" + campaignId + "/issues/claim", userId);
    }

    private ResponseEntity<ApiResponse<List<CouponV1Dto.CouponResponse>>> myCoupons(Long userId) {
        return testRestTemplate.exchange("/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(null, headers(userId)), LIST_TYPE);
    }

    private static void assertFail(ResponseEntity<ApiResponse<Map<String, Object>>> response, HttpStatus status, String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        assertThat(response.getBody().meta().errorCode()).isEqualTo(errorCode);
        assertThat(response.getBody().data()).isNull();
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{id}/issues")
    @Nested
    class Apply {
        @DisplayName("처음 접수하면 202 와 {seq}, meta.message 에 '접수 ≠ 발급 보장' 안내가 담긴다. 다시 접수하면 200 과 같은 seq, message 없음.")
        @Test
        void acceptedThenAlreadyAccepted() {
            Long userId = saveUser();

            ResponseEntity<ApiResponse<Map<String, Object>>> first = apply(userId);
            ResponseEntity<ApiResponse<Map<String, Object>>> retry = apply(userId);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(first.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
            assertThat(first.getBody().meta().message()).isEqualTo(CouponV1Controller.ACCEPTED_MESSAGE);
            assertThat(first.getBody().data()).containsEntry("seq", 1);
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retry.getBody().meta().message()).isNull();
            assertThat(retry.getBody().data()).containsEntry("seq", 1);
        }

        @DisplayName("이미 발급된 사용자의 접수는 200 과 {couponId} 다.")
        @Test
        void alreadyIssued() {
            Long userId = saveUser();
            apply(userId);
            queue.promote(campaignId, gen);
            Integer couponId = (Integer) claim(userId).getBody().data().get("couponId");

            ResponseEntity<ApiResponse<Map<String, Object>>> response = apply(userId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).containsEntry("couponId", couponId);
        }

        @DisplayName("gate 가 닫혀 있으면 503 'Service Unavailable', 헤더가 없으면 401, 없는 캠페인은 404 다.")
        @Test
        void failures() {
            Long userId = saveUser();
            gate.close(campaignId);
            assertFail(apply(userId), HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable");

            gate.open(campaignId);
            assertFail(apply(null), HttpStatus.UNAUTHORIZED, "Unauthorized");
            assertFail(post("/api/v1/coupon-campaigns/999999/issues", userId), HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    @DisplayName("GET /api/v1/coupon-campaigns/{id}/issues/me")
    @Nested
    class StatusQuery {
        @DisplayName("접수 전 NONE, 접수 후 WAITING+rank, 승격 후 ADMITTED, 발급 후 ISSUED+couponId. 쓰지 않는 필드는 응답에서 생략된다.")
        @Test
        void lifecycle() {
            Long userId = saveUser();
            assertThat(status(userId).getBody().data()).containsEntry("status", "NONE").doesNotContainKeys("rank", "couponId");

            apply(userId);
            assertThat(status(userId).getBody().data()).containsEntry("status", "WAITING").containsEntry("rank", 0);

            queue.promote(campaignId, gen);
            assertThat(status(userId).getBody().data()).containsEntry("status", "ADMITTED").doesNotContainKey("rank");

            Integer couponId = (Integer) claim(userId).getBody().data().get("couponId");
            assertThat(status(userId).getBody().data()).containsEntry("status", "ISSUED").containsEntry("couponId", couponId);
        }

        @DisplayName("다른 사용자의 접수·발급은 내 상태 조회에 나타나지 않는다. 본인 헤더 기준으로만 답한다.")
        @Test
        void doesNotExposeOthers() {
            Long owner = saveUser();
            Long other = saveUser();
            apply(owner);
            queue.promote(campaignId, gen);
            claim(owner);

            ResponseEntity<ApiResponse<Map<String, Object>>> response = status(other);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).containsEntry("status", "NONE").doesNotContainKey("couponId");
        }
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{id}/issues/claim")
    @Nested
    class Claim {
        @DisplayName("승격된 사용자는 201 과 {couponId}, 재요청은 200 과 같은 couponId 다.")
        @Test
        void createdThenOk() {
            Long userId = saveUser();
            apply(userId);
            queue.promote(campaignId, gen);

            ResponseEntity<ApiResponse<Map<String, Object>>> first = claim(userId);
            ResponseEntity<ApiResponse<Map<String, Object>>> retry = claim(userId);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(first.getBody().data().get("couponId")).isNotNull();
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retry.getBody().data()).isEqualTo(first.getBody().data());
        }

        @DisplayName("승격되지 않은 사용자의 claim 은 409 'Conflict' 이고, 그 뒤에도 상태는 WAITING 그대로다.")
        @Test
        void notAdmitted() {
            Long userId = saveUser();
            apply(userId);

            assertFail(claim(userId), HttpStatus.CONFLICT, "Conflict");

            assertThat(status(userId).getBody().data()).containsEntry("status", "WAITING");
        }
    }

    @DisplayName("GET /api/v1/coupons")
    @Nested
    class MyCoupons {
        @DisplayName("본인 쿠폰만 돌려준다. 타인의 쿠폰은 목록에 없고, gate 가 닫혀 있어도 200 이다 (INV-W8-05).")
        @Test
        void returnsOnlyOwnCoupons_evenWhenGateClosed() {
            Long owner = saveUser();
            Long other = saveUser();
            apply(owner);
            apply(other);
            queue.promote(campaignId, gen);
            claim(owner);
            claim(other);
            gate.close(campaignId);

            ResponseEntity<ApiResponse<List<CouponV1Dto.CouponResponse>>> response = myCoupons(owner);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).hasSize(1);
            CouponV1Dto.CouponResponse coupon = response.getBody().data().get(0);
            assertThat(coupon.campaignId()).isEqualTo(campaignId);
            assertThat(coupon.benefitType()).isEqualTo("FIXED");
            assertThat(coupon.benefitValue()).isEqualTo(1000L);
            assertThat(coupon.status()).isEqualTo("ISSUED");
            assertThat(coupon.expiresAt()).isEqualTo(EXPIRES_AT);
            assertThat(myCoupons(saveUser()).getBody().data()).isEmpty();
        }
    }
}
