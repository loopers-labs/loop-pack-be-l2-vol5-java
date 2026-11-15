package com.loopers.interfaces.api;

import com.loopers.domain.coupon.CouponCampaignService;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisSemaphoreGate;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.support.coupon.BarrierIssueConfig;
import com.loopers.support.coupon.BarrierIssueService;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 속도 제한(RATE_LIMITED)과 자리 없음(NO_PERMIT)이 서로 다른 429로 구분되는지 실제 HTTP·MySQL·Redis로 확인한다.
 * 한도는 10초에 3건으로 두어 테스트가 도는 동안 다시 채워지지 않게 하고, 동시 실행 수(C)는 3이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BarrierIssueConfig.class)
@TestPropertySource(properties = {
    "coupon.admission.capacity=3",
    "coupon.rate-limit.enabled=true",
    "coupon.rate-limit.limit-for-period=3",
    "coupon.rate-limit.refresh-period=10s"
})
class CouponRateLimitV1ApiE2ETest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BarrierIssueService barrier;

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    private CouponCampaignService campaignService;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
    }

    @AfterEach
    void tearDown() {
        barrier.reset();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> issue(Long campaignId, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return testRestTemplate.exchange(
            "/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    @DisplayName("한도(3건)를 넘은 발급 요청은 429 RATE_LIMITED와 남은 시간만큼의 Retry-After로 거절되어 Redis·DB에 닿지 않고, 목록 조회는 제한하지 않으며, 자리 없음(NO_PERMIT)과는 오류 코드로 구분된다.")
    @Test
    void rateLimitedIsDistinguishedFromNoPermit() throws Exception {
        // arrange — 세 요청 모두 DB 진입 직전에 붙잡아 자리(C=3)를 모두 차지하게 한다. 이 세 요청이 속도 제한 한도(3건)를 모두 쓴다
        Long campaignId = campaignService.create("속도 제한", 100, "혜택").getId();
        for (long userId = 1; userId <= 3; userId++) {
            barrier.holds.put(userId, new CompletableFuture<>());
        }
        var inFlight = new java.util.ArrayList<CompletableFuture<ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>>>>();
        for (long userId = 1; userId <= 3; userId++) {
            long id = userId;
            inFlight.add(CompletableFuture.supplyAsync(() -> issue(campaignId, id)));
        }
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> assertThat(barrier.entered).containsExactlyInAnyOrder(1L, 2L, 3L));
        assertThat(gate.activeCount()).isEqualTo(3);

        try {
            // act — 한도를 모두 쓴 뒤의 네 번째 발급 요청, 그리고 같은 시점의 목록 조회
            var limited = issue(campaignId, 4L);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-USER-ID", "1");
            var list = testRestTemplate.exchange(
                "/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(null, headers),
                new ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponListResponse>>() {}
            );

            // assert — 속도 제한 429: 오류 코드가 RATE_LIMITED, Retry-After는 한도가 다시 채워질 때까지의 남은 시간(1~10초)
            assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(limited.getBody().meta().errorCode()).isEqualTo("RATE_LIMITED");
            long retryAfter = Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            assertThat(retryAfter).isBetween(1L, 10L);
            assertThat(barrier.entered).as("컨트롤러·Redis·DB에 닿지 않았다").doesNotContain(4L);
            assertThat(gate.activeCount()).as("자리도 더 차지하지 않았다").isEqualTo(3);
            assertThat(list.getStatusCode()).as("목록 조회는 속도 제한 대상이 아니다").isEqualTo(HttpStatus.OK);
        } finally {
            barrier.holds.values().forEach(hold -> hold.complete(null));
        }
        for (var request : inFlight) {
            assertThat(request.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(couponJpaRepository.count()).isEqualTo(3);
        assertThat(campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(3);
    }
}
