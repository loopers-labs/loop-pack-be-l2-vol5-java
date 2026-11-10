package com.loopers.interfaces.api.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.promotion.PromotionModel;
import com.loopers.domain.promotion.PromotionRepository;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 프로모션 생성(관리자)·선착순 쿠폰 발급·내 쿠폰 조회의 HTTP 계약 (8장). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromotionV1ApiE2ETest {

    private static final String CREATE_PATH = "/api-admin/v1/promotions";
    private static final String COUPONS_PATH = "/api/v1/coupons";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private PromotionRepository promotionRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisCleanUp redisCleanUp;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> masterRedisTemplate;
    @Autowired
    private CouponIssueLimiterProperties limiterProperties;

    private ApiTestClient api;
    private UserModel user;
    private UserModel admin;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
        user = fixtures.user();
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    /** 발급 세마포어(DR-60)의 허가를 다른 요청이 모두 쥔 상태로 만든다. 1 분 뒤 만료. */
    private void occupyAllPermits(long promotionId) {
        String key = "coupon-issue:semaphore:" + promotionId;
        long expiresAt = System.currentTimeMillis() + 60_000;
        for (int i = 0; i < limiterProperties.permits(); i++) {
            masterRedisTemplate.opsForZSet().add(key, "held-" + i, expiresAt);
        }
    }

    private static Map<String, Object> createBody(String name, int totalQuantity, int perUserLimit, Map<String, Object> spec) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", "COUPON_ISSUE");
        body.put("startsAt", "2026-11-10T10:00:00+09:00");
        body.put("endsAt", "2026-11-17T10:00:00+09:00");
        body.put("couponIssue", Map.of("totalQuantity", totalQuantity, "perUserLimit", perUserLimit, "couponSpec", spec));
        return body;
    }

    private long createPromotion(String name, int totalQuantity) {
        var created = api.post(CREATE_PATH, admin.getId(), createBody(name, totalQuantity, 1, Map.of("discountValue", 3000)))
            .assertSuccess(HttpStatus.OK);
        return created.data().path("promotionId").asLong();
    }

    private static String issuePath(Object promotionId) {
        return "/api/v1/promotions/" + promotionId + "/issues";
    }

    @Nested
    @DisplayName("POST /api-admin/v1/promotions")
    class Create {
        @DisplayName("쿠폰 발급 프로모션을 만들면 ACTIVE 이고, 설정과 쿠폰 속성(중첩 포함)을 그대로 돌려준다.")
        @Test
        void createsActivePromotion() {
            Map<String, Object> spec = Map.of("discount", Map.of("type", "FIXED", "value", 3000), "validDays", 7);

            var created = api.post(CREATE_PATH, admin.getId(), createBody("11월 선착순 쿠폰", 100, 1, spec)).assertSuccess(HttpStatus.OK);

            JsonNode data = created.data();
            assertThat(data.path("promotionId").asLong()).isPositive();
            assertThat(data.path("name").asText()).isEqualTo("11월 선착순 쿠폰");
            assertThat(data.path("type").asText()).isEqualTo("COUPON_ISSUE");
            assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(data.path("totalQuantity").asInt()).isEqualTo(100);
            assertThat(data.path("perUserLimit").asInt()).isEqualTo(1);
            assertThat(data.path("couponSpec").path("discount").path("value").asInt()).isEqualTo(3000);
            assertThat(data.path("couponSpec").path("validDays").asInt()).isEqualTo(7);
            assertThat(promotionRepository.findStock(data.path("promotionId").asLong()).orElseThrow().getRemaining()).isEqualTo(100);
        }

        @DisplayName("[INVALID_PROMOTION] 이름 없음·기간 역전·한도 0·1인당 한도 2·빈 쿠폰 속성·설정 없음은 400 이고 아무것도 만들지 않는다.")
        @Test
        void invalidPromotion() {
            api.post(CREATE_PATH, admin.getId(), createBody("", 10, 1, Map.of("k", "v"))).assertError(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION");
            Map<String, Object> reversed = createBody("p", 10, 1, Map.of("k", "v"));
            reversed.put("endsAt", "2026-11-01T10:00:00+09:00");
            api.post(CREATE_PATH, admin.getId(), reversed).assertError(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION");
            api.post(CREATE_PATH, admin.getId(), createBody("p", 0, 1, Map.of("k", "v"))).assertError(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION");
            api.post(CREATE_PATH, admin.getId(), createBody("p", 10, 2, Map.of("k", "v"))).assertError(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION");
            api.post(CREATE_PATH, admin.getId(), createBody("p", 10, 1, Map.of())).assertError(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION");
            Map<String, Object> noSetting = createBody("p", 10, 1, Map.of("k", "v"));
            noSetting.remove("couponIssue");
            api.post(CREATE_PATH, admin.getId(), noSetting).assertError(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION");

            assertThat(promotionRepository.find(1L)).isEmpty();
        }

        @DisplayName("[NOT_ADMIN] 관리자가 아니면 403 이다.")
        @Test
        void notAdmin() {
            api.post(CREATE_PATH, user.getId(), createBody("p", 10, 1, Map.of("k", "v"))).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/promotions/{promotionId}/issues")
    class Issue {
        @DisplayName("발급하면 couponId·promotionId·issuedAt 을 돌려주고 남은 수량이 1 줄어든다.")
        @Test
        void issuesCoupon() {
            long promotionId = createPromotion("선착순", 10);

            var issued = api.post(issuePath(promotionId), user.getId(), null).assertSuccess(HttpStatus.OK);

            assertThat(issued.data().path("couponId").asLong()).isPositive();
            assertThat(issued.data().path("promotionId").asLong()).isEqualTo(promotionId);
            assertThat(issued.data().path("issuedAt").asText()).isNotBlank();
            assertThat(promotionRepository.findStock(promotionId).orElseThrow().getRemaining()).isEqualTo(9);
        }

        @DisplayName("[COUPON_ALREADY_ISSUED] 같은 사용자가 다시 요청하면 409 이다.")
        @Test
        void alreadyIssued() {
            long promotionId = createPromotion("선착순", 10);
            api.post(issuePath(promotionId), user.getId(), null).assertSuccess(HttpStatus.OK);

            api.post(issuePath(promotionId), user.getId(), null).assertError(HttpStatus.CONFLICT, "COUPON_ALREADY_ISSUED");
        }

        @DisplayName("[COUPON_ISSUE_BUSY] 발급 허가가 모두 차 있으면 재시도 뒤 503 이고, 발급 내역·수량은 그대로다.")
        @Test
        void busy() {
            long promotionId = createPromotion("선착순", 10);
            occupyAllPermits(promotionId);

            api.post(issuePath(promotionId), user.getId(), null).assertError(HttpStatus.SERVICE_UNAVAILABLE, "COUPON_ISSUE_BUSY");

            assertThat(promotionRepository.existsIssuedCoupon(user.getId(), promotionId)).isFalse();
            assertThat(promotionRepository.findStock(promotionId).orElseThrow().getRemaining()).isEqualTo(10);
        }

        @DisplayName("[COUPON_ALREADY_ISSUED] 이미 받은 사용자는 발급 허가가 모두 차 있어도 허가를 기다리지 않고 409 이다.")
        @Test
        void alreadyIssued_beforeLimiter() {
            long promotionId = createPromotion("선착순", 10);
            api.post(issuePath(promotionId), user.getId(), null).assertSuccess(HttpStatus.OK);
            occupyAllPermits(promotionId);

            api.post(issuePath(promotionId), user.getId(), null).assertError(HttpStatus.CONFLICT, "COUPON_ALREADY_ISSUED");
        }

        @DisplayName("[COUPON_SOLD_OUT] 한도를 다 쓰면 다음 사용자는 409 이다.")
        @Test
        void soldOut() {
            long promotionId = createPromotion("선착순", 1);
            api.post(issuePath(promotionId), user.getId(), null).assertSuccess(HttpStatus.OK);

            api.post(issuePath(promotionId), fixtures.user().getId(), null).assertError(HttpStatus.CONFLICT, "COUPON_SOLD_OUT");
        }

        @DisplayName("[PROMOTION_NOT_ACTIVE] ACTIVE 가 아니면 409 이다.")
        @Test
        void notActive() {
            long promotionId = createPromotion("선착순", 10);
            transactionTemplate.executeWithoutResult(s -> {
                PromotionModel promotion = promotionRepository.find(promotionId).orElseThrow();
                promotion.end();
                promotionRepository.save(promotion);
            });

            api.post(issuePath(promotionId), user.getId(), null).assertError(HttpStatus.CONFLICT, "PROMOTION_NOT_ACTIVE");
        }

        @DisplayName("[PROMOTION_NOT_FOUND] 없는 프로모션은 404 이다.")
        @Test
        void notFound() {
            api.post(issuePath(999_999), user.getId(), null).assertError(HttpStatus.NOT_FOUND, "PROMOTION_NOT_FOUND");
        }

        @DisplayName("[USER_NOT_FOUND] 요청자가 없으면 404 이고 수량은 그대로다.")
        @Test
        void userNotFound() {
            long promotionId = createPromotion("선착순", 10);

            api.post(issuePath(promotionId), 999_999L, null).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

            assertThat(promotionRepository.findStock(promotionId).orElseThrow().getRemaining()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/coupons")
    class MyCoupons {
        @DisplayName("내가 받은 쿠폰만 최근 발급 순으로, 프로모션 이름과 쿠폰 속성을 함께 돌려준다.")
        @Test
        void listsMyCoupons() {
            long first = createPromotion("첫 번째", 10);
            long second = createPromotion("두 번째", 10);
            api.post(issuePath(first), user.getId(), null).assertSuccess(HttpStatus.OK);
            api.post(issuePath(second), user.getId(), null).assertSuccess(HttpStatus.OK);
            api.post(issuePath(first), fixtures.user().getId(), null).assertSuccess(HttpStatus.OK);

            var result = api.get(COUPONS_PATH, user.getId()).assertSuccess(HttpStatus.OK);

            JsonNode data = result.data();
            assertThat(data).hasSize(2);
            assertThat(List.of(data.get(0).path("promotionId").asLong(), data.get(1).path("promotionId").asLong()))
                .containsExactly(second, first);
            assertThat(data.get(0).path("promotionName").asText()).isEqualTo("두 번째");
            assertThat(data.get(0).path("couponSpec").path("discountValue").asInt()).isEqualTo(3000);
            assertThat(data.get(0).path("couponId").asLong()).isPositive();
            assertThat(data.get(0).path("issuedAt").asText()).isNotBlank();
        }

        @DisplayName("받은 쿠폰이 없으면 빈 목록이다.")
        @Test
        void emptyWhenNone() {
            var result = api.get(COUPONS_PATH, user.getId()).assertSuccess(HttpStatus.OK);

            assertThat(result.data()).isEmpty();
        }

        @DisplayName("[USER_NOT_FOUND] 요청자가 없으면 404 이다.")
        @Test
        void userNotFound() {
            api.get(COUPONS_PATH, 999_999L).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
    }
}
