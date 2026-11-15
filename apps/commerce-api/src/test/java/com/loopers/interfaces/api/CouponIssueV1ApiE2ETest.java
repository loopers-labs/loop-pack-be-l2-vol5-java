package com.loopers.interfaces.api;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignService;
import com.loopers.domain.coupon.CouponIssueResult;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.domain.coupon.CouponIssueTransactions;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisSemaphoreGate;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * POST /api/v1/coupon-campaigns/{id}/issues 를 실제 HTTP·MySQL·Redis로 확인한다. Rate limiter는 아직 없다.
 * C=3 수용 실험의 barrier는 "Redis 획득 직후, DB TX 시작 전"인 CouponIssueService.issue 진입 지점에 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "coupon.admission.capacity=3")
class CouponIssueV1ApiE2ETest {

    private static final Duration DEADLINE = Duration.ofSeconds(20);

    /** 지정한 사용자의 실행만 DB 발급 진입 직전에 붙잡는 테스트용 서비스. 붙잡는 대기에는 deadline이 있다. */
    static class BarrierIssueService extends CouponIssueService {
        final Map<Long, CompletableFuture<Void>> holds = new ConcurrentHashMap<>();
        final Set<Long> entered = ConcurrentHashMap.newKeySet();

        BarrierIssueService(CouponIssueTransactions transactions) {
            super(transactions);
        }

        @Override
        public CouponIssueResult issue(Long campaignId, Long userId) {
            entered.add(userId);
            CompletableFuture<Void> hold = holds.get(userId);
            if (hold != null) {
                try {
                    hold.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("barrier deadline 초과 또는 중단: userId=" + userId, e);
                }
            }
            return super.issue(campaignId, userId);
        }
    }

    @TestConfiguration
    static class BarrierConfig {
        @Bean
        @Primary
        BarrierIssueService barrierIssueService(CouponIssueTransactions transactions) {
            return new BarrierIssueService(transactions);
        }
    }

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
        barrier.holds.values().forEach(hold -> hold.complete(null));
        barrier.holds.clear();
        barrier.entered.clear();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> issue(Long campaignId, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", String.valueOf(userId));
        }
        return testRestTemplate.exchange(
            "/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> myCoupons(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return testRestTemplate.exchange(
            "/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(null, headers), new ParameterizedTypeReference<>() {}
        );
    }

    private static String errorCode(ResponseEntity<? extends ApiResponse<?>> response) {
        return response.getBody().meta().errorCode();
    }

    private int issuedCount(Long campaignId) {
        return campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount();
    }

    @DisplayName("처음 요청하면 201과 couponId를 받고 내 목록에 같은 ID가 보이며, 같은 사용자의 재요청은 200과 같은 couponId이고 자리는 모두 반환된다.")
    @Test
    void issuesWith201_thenReturns200WithSameCoupon() {
        // arrange
        Long campaignId = campaignService.create("신규", 10, "5,000원 할인권").getId();

        // act
        var first = issue(campaignId, 1L);
        var second = issue(campaignId, 1L);

        // assert
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long couponId = first.getBody().data().couponId();
        assertThat(second.getBody().data().couponId()).isEqualTo(couponId);
        assertThat(myCoupons(1L).getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId).containsExactly(couponId);
        assertThat(couponJpaRepository.count()).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(gate.activeCount()).as("자리 반환").isZero();
        assertThat(barrier.entered).as("재요청은 자리를 차지하거나 발급 경로에 들어가지 않는다").containsExactly(1L);
    }

    @DisplayName("품절은 409 SOLD_OUT, 비활성은 409 CAMPAIGN_INACTIVE, 없는 캠페인은 404이며, 이미 받은 사용자는 품절 뒤에도 200이다.")
    @Test
    void distinguishesBusinessRejections() {
        // arrange
        Long soldOutId = campaignService.create("한 장", 1, "혜택").getId();
        CouponCampaignModel inactive = new CouponCampaignModel("끝남", 5, "혜택");
        inactive.deactivate();
        Long inactiveId = campaignJpaRepository.save(inactive).getId();
        var winner = issue(soldOutId, 1L);

        // act
        var soldOut = issue(soldOutId, 2L);
        var inactiveResponse = issue(inactiveId, 3L);
        var missing = issue(9_999L, 3L);
        var winnerAgain = issue(soldOutId, 1L);

        // assert
        assertThat(winner.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(soldOut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(soldOut)).isEqualTo("SOLD_OUT");
        assertThat(inactiveResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(inactiveResponse)).isEqualTo("CAMPAIGN_INACTIVE");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(winnerAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(winnerAgain.getBody().data().couponId()).isEqualTo(winner.getBody().data().couponId());
        assertThat(couponJpaRepository.count()).isEqualTo(1);
        assertThat(issuedCount(soldOutId)).isEqualTo(1);
        assertThat(gate.activeCount()).as("거절된 요청의 자리도 반환").isZero();
    }

    @DisplayName("X-USER-ID 헤더가 없으면 400이다.")
    @Test
    void rejectsRequestWithoutUserHeader() {
        // arrange
        Long campaignId = campaignService.create("신규", 10, "혜택").getId();

        // act & assert
        assertThat(issue(campaignId, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(couponJpaRepository.count()).isZero();
    }

    @DisplayName("C=3에서 세 실행이 DB 진입 직전에 붙잡힌 상태로 네 번째가 오면 429 NO_PERMIT이고 DB 경로에 들어가지 않으며, 한 실행이 끝나 자리가 나면 네 번째 재요청이 쿠폰을 받는다.")
    @Test
    void fourthRequestIsRejectedWhileThreeAreHeld_thenEntersFreedSlot() {
        // arrange — 캠페인 한도는 실험을 가리지 않게 충분히 크게 둔다
        Long campaignId = campaignService.create("수용 실험", 100, "혜택").getId();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (long userId = 1; userId <= 3; userId++) {
                barrier.holds.put(userId, new CompletableFuture<>());
            }

            // act 1 — 서로 다른 세 사용자의 실제 요청을 보내 Redis 획득 직후 DB 진입 전에 붙잡는다
            Map<Long, CompletableFuture<ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>>>> inFlight = new ConcurrentHashMap<>();
            for (long userId = 1; userId <= 3; userId++) {
                long id = userId;
                inFlight.put(id, CompletableFuture.supplyAsync(() -> issue(campaignId, id), executor));
            }
            await().atMost(DEADLINE).untilAsserted(() -> assertThat(barrier.entered).containsExactlyInAnyOrder(1L, 2L, 3L));

            // assert 1 — 활성 token 3개. 네 번째 사용자는 429이고 DB 발급 경로에 들어가지 않으며 쿠폰도 없다
            assertThat(gate.activeCount()).isEqualTo(3);
            var rejected = issue(campaignId, 4L);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(errorCode(rejected)).isEqualTo("NO_PERMIT");
            assertThat(rejected.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(barrier.entered).as("네 번째는 DB 발급 경로에 진입하지 않았다").doesNotContain(4L);
            assertThat(couponJpaRepository.count()).isZero();
            assertThat(gate.activeCount()).isEqualTo(3);

            // act 2 — 한 실행을 진행시켜 DB 발급·종료·반환을 확인한다
            barrier.holds.get(1L).complete(null);
            assertThat(inFlight.get(1L).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            await().atMost(DEADLINE).untilAsserted(() -> assertThat(gate.activeCount()).isEqualTo(2));
            assertThat(couponJpaRepository.count()).isEqualTo(1);

            // act 3 — 네 번째 사용자가 다시 요청하면 빈자리에 들어가 쿠폰을 받는다
            var retry = issue(campaignId, 4L);
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // act 4 — 나머지 실행도 종료한다
            barrier.holds.get(2L).complete(null);
            barrier.holds.get(3L).complete(null);
            assertThat(inFlight.get(2L).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(inFlight.get(3L).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // assert 4 — 쿠폰 4장, 발급 수 4, 활성 token 0
            assertThat(couponJpaRepository.count()).isEqualTo(4);
            assertThat(issuedCount(campaignId)).isEqualTo(4);
            await().atMost(DEADLINE).untilAsserted(() -> assertThat(gate.activeCount()).isZero());
        } catch (Exception e) {
            throw new AssertionError("수용 실험 중 오류", e);
        } finally {
            barrier.holds.values().forEach(hold -> hold.complete(null));
            executor.shutdownNow();
        }
    }
}
