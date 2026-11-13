package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
import com.loopers.application.coupon.CouponIssueTransaction;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.infrastructure.coupon.RedisCouponIssuePermits;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "coupon.issue.capacity=3",
    "coupon.issue.admission.open-on-startup=false"
})
@AutoConfigureMockMvc
class CouponIssueV1ApiE2ETest {

    private static final String ISSUE_PATH = "/api/v1/coupon-campaigns/%d/issues";
    private static final String COUPONS_PATH = "/api/v1/coupons";
    private static final Long FIRST_USER_ID = 71001L;
    private static final Long SECOND_USER_ID = 71002L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponRepository coupons;
    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private ApplicationTime applicationTime;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private CouponIssuePermits permitStore;
    @MockitoSpyBean private CouponIssueTransaction issueTransaction;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long campaignId;
    private final List<Long> additionalCampaignIds = new ArrayList<>();

    @BeforeEach
    void prepareCampaign() {
        redisTemplate.delete(RedisCouponIssuePermits.PERMITS_KEY);
        admission.close();
        Instant now = applicationTime.now().toInstant();
        CouponCampaign campaign = campaigns.save(CouponCampaign.create("coupon-api-" + System.nanoTime(),
            5, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        campaignId = campaign.getId();
        entityManager.clear();
        admission.open();
    }

    @AfterEach
    void cleanUpOwnData() {
        admission.close();
        redisTemplate.delete(RedisCouponIssuePermits.PERMITS_KEY);
        jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
        jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        for (Long additionalCampaignId : additionalCampaignIds) {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", additionalCampaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", additionalCampaignId);
        }
    }

    @DisplayName("신규 발급은 201, 재요청은 기존 couponId의 200을 반환하고 본인 목록에서 확인된다.")
    @Test
    void issuesIdempotentlyAndListsOnlyTheRequestingUser() throws Exception {
        JsonNode first = data(mockMvc.perform(post(ISSUE_PATH.formatted(campaignId))
                .header("X-USER-ID", FIRST_USER_ID))
            .andExpect(status().isCreated()).andReturn());
        JsonNode secondUser = data(mockMvc.perform(post(ISSUE_PATH.formatted(campaignId))
                .header("X-USER-ID", SECOND_USER_ID))
            .andExpect(status().isCreated()).andReturn());
        for (long userId = 71003L; userId <= 71005L; userId++) {
            mockMvc.perform(post(ISSUE_PATH.formatted(campaignId)).header("X-USER-ID", userId))
                .andExpect(status().isCreated());
        }
        JsonNode repeated = data(mockMvc.perform(post(ISSUE_PATH.formatted(campaignId))
                .header("X-USER-ID", FIRST_USER_ID))
            .andExpect(status().isOk()).andReturn());

        assertThat(first.path("couponId").asLong()).isEqualTo(repeated.path("couponId").asLong());
        assertThat(first.path("benefitDescription").asText()).isEqualTo("5,000원 할인권");
        assertThat(secondUser.path("couponId").asLong()).isNotEqualTo(first.path("couponId").asLong());
        assertThat(coupons.countIssuedByCampaignId(campaignId)).isEqualTo(5);
        assertThat(campaigns.findById(campaignId).orElseThrow().getTerminatedAt()).isNotNull();

        admission.close();
        JsonNode list = objectMapper.readTree(mockMvc.perform(get(COUPONS_PATH)
                .header("X-USER-ID", FIRST_USER_ID))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.path("data").size()).isEqualTo(1);
        assertThat(list.path("data").get(0).path("couponId").asLong()).isEqualTo(first.path("couponId").asLong());
    }

    @DisplayName("요청자 헤더가 없으면 발급과 목록을 거절한다.")
    @Test
    void rejectsMissingUserHeader() throws Exception {
        mockMvc.perform(post(ISSUE_PATH.formatted(campaignId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get(COUPONS_PATH))
            .andExpect(status().isBadRequest());
        assertThat(coupons.countIssuedByCampaignId(campaignId)).isZero();
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    @DisplayName("없는 캠페인과 발급 기간이 지난 캠페인은 permit 전에 404·409로 거절한다.")
    @Test
    void rejectsCampaignBeforeAcquiringPermit() throws Exception {
        mockMvc.perform(post(ISSUE_PATH.formatted(Long.MAX_VALUE)).header("X-USER-ID", FIRST_USER_ID))
            .andExpect(status().isNotFound());

        Instant now = applicationTime.now().toInstant();
        CouponCampaign expired = campaigns.save(CouponCampaign.create("coupon-api-expired-" + System.nanoTime(),
            1, "5,000원 할인권", now.minusSeconds(3600), now.minusSeconds(1)));
        additionalCampaignIds.add(expired.getId());
        mockMvc.perform(post(ISSUE_PATH.formatted(expired.getId())).header("X-USER-ID", FIRST_USER_ID))
            .andExpect(status().isConflict());

        assertThat(coupons.countIssuedByCampaignId(campaignId)).isZero();
        assertThat(coupons.countIssuedByCampaignId(expired.getId())).isZero();
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    @DisplayName("Redis permit 세 자리가 사용 중이면 429로 끝내고 DB 발급에 진입하지 않는다.")
    @Test
    void rejectsWhenRedisPermitSetIsFull() throws Exception {
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, "held-a", "held-b", "held-c");

        mockMvc.perform(post(ISSUE_PATH.formatted(campaignId))
                .header("X-USER-ID", FIRST_USER_ID))
            .andExpect(status().isTooManyRequests());

        assertThat(coupons.findIssuedByCampaignIdAndUserId(campaignId, FIRST_USER_ID)).isEmpty();
        assertThat(coupons.countIssuedByCampaignId(campaignId)).isZero();
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isEqualTo(3L);
    }

    @DisplayName("Redis permit을 세 실행이 유지하는 동안 네 번째 요청은 거절되고 빈자리에서 다시 발급된다.")
    @Test
    void admitsThreeConcurrentIssueExecutionsAndRejectsTheFourth() throws Exception {
        BlockingQueue<String> acquiredTokens = new LinkedBlockingQueue<>();
        ConcurrentMap<String, CountDownLatch> continueByToken = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            String token = invocation.getArgument(0);
            boolean acquired = (boolean) invocation.callRealMethod();
            if (acquired) {
                CountDownLatch continueToDatabase = new CountDownLatch(1);
                continueByToken.put(token, continueToDatabase);
                acquiredTokens.add(token);
                if (!continueToDatabase.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("DB 진입 전 수용 barrier가 시간 안에 해제되지 않았습니다.");
                }
            }
            return acquired;
        }).when(permitStore).tryAcquire(anyString(), anyInt());

        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorCompletionService<Integer> completedRequests = new ExecutorCompletionService<>(workers);
        try {
            for (long userId = 71100L; userId < 71103L; userId++) {
                long requestUserId = userId;
                completedRequests.submit(() -> issueStatus(requestUserId));
            }

            Set<String> initialTokens = Set.of(awaitToken(acquiredTokens), awaitToken(acquiredTokens),
                awaitToken(acquiredTokens));
            assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
                .containsExactlyInAnyOrderElementsOf(initialTokens);
            assertThat(redisTemplate.getExpire(RedisCouponIssuePermits.PERMITS_KEY, TimeUnit.SECONDS)).isEqualTo(-1L);
            assertThat(coupons.countIssuedByCampaignId(campaignId)).isZero();
            assertThat(issueStatus(71103L)).isEqualTo(429);
            assertThat(coupons.findIssuedByCampaignIdAndUserId(campaignId, 71103L)).isEmpty();
            verify(issueTransaction, never()).issue(eq(campaignId), eq(71103L), any());

            String releasedToken = initialTokens.iterator().next();
            continueByToken.get(releasedToken).countDown();
            assertThat(completedRequests.take().get(10, TimeUnit.SECONDS)).isEqualTo(201);

            Set<String> remainingTokens = new HashSet<>(initialTokens);
            remainingTokens.remove(releasedToken);
            assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
                .containsExactlyInAnyOrderElementsOf(remainingTokens);
            assertThat(coupons.countIssuedByCampaignId(campaignId)).isEqualTo(1);
            assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);

            completedRequests.submit(() -> issueStatus(71103L));
            String retriedFourthToken = awaitToken(acquiredTokens);
            Set<String> occupiedAfterRetry = new HashSet<>(remainingTokens);
            occupiedAfterRetry.add(retriedFourthToken);
            assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
                .containsExactlyInAnyOrderElementsOf(occupiedAfterRetry);
            verify(issueTransaction, never()).issue(eq(campaignId), eq(71103L), any());

            continueByToken.values().forEach(CountDownLatch::countDown);
            for (int completed = 0; completed < 3; completed++) {
                assertThat(completedRequests.take().get(10, TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            continueByToken.values().forEach(CountDownLatch::countDown);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(coupons.countIssuedByCampaignId(campaignId)).isEqualTo(4);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(4);
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    @DisplayName("자기 token을 반복 반환하거나 모르는 token을 반환해도 다른 실행의 자리는 유지된다.")
    @Test
    void releaseRemovesOnlyTheRequestedToken() {
        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, "active-a", "active-b");

        assertThat(permitStore.release("active-a")).isEqualTo(1);
        assertThat(permitStore.release("active-a")).isZero();
        assertThat(permitStore.release("unknown-token")).isZero();

        assertThat(redisTemplate.opsForSet().members(RedisCouponIssuePermits.PERMITS_KEY))
            .containsExactly("active-b");
    }

    @DisplayName("같은 사용자의 동시 신규 요청 두 개는 한 쿠폰만 만들고 같은 couponId를 반환한다.")
    @Test
    void concurrentDuplicateRequestsReturnTheSameCoupon() throws Exception {
        CountDownLatch twoPermitsAcquired = new CountDownLatch(2);
        CountDownLatch continueToDatabase = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean acquired = (boolean) invocation.callRealMethod();
            if (acquired) {
                twoPermitsAcquired.countDown();
                if (!continueToDatabase.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 중복 요청 barrier가 시간 안에 해제되지 않았습니다.");
                }
            }
            return acquired;
        }).when(permitStore).tryAcquire(anyString(), anyInt());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> requests = new ArrayList<>();
        try {
            for (int request = 0; request < 2; request++) {
                requests.add(workers.submit(() -> mockMvc.perform(post(ISSUE_PATH.formatted(campaignId))
                    .header("X-USER-ID", FIRST_USER_ID)).andReturn()));
            }
            assertThat(twoPermitsAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            continueToDatabase.countDown();

            List<MvcResult> results = requests.stream().map(this::await).toList();
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 200);
            List<Long> issuedIds = results.stream().map(result -> {
                try {
                    return data(result).path("couponId").asLong();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
            assertThat(issuedIds).containsOnly(issuedIds.getFirst());
        } finally {
            continueToDatabase.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(coupons.countIssuedByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    @DisplayName("마지막 한 장을 같은 사용자가 동시에 요청해도 두 번째 응답은 기존 쿠폰이다.")
    @Test
    void concurrentDuplicateRequestsForLastCouponReturnTheSameCoupon() throws Exception {
        Instant now = applicationTime.now().toInstant();
        CouponCampaign lastCouponCampaign = campaigns.save(CouponCampaign.create(
            "coupon-api-last-duplicate-" + System.nanoTime(),
            1, "5,000원 할인권", now.minusSeconds(60), now.plusSeconds(3600)));
        additionalCampaignIds.add(lastCouponCampaign.getId());
        entityManager.clear();

        CountDownLatch twoPermitsAcquired = new CountDownLatch(2);
        CountDownLatch continueToDatabase = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean acquired = (boolean) invocation.callRealMethod();
            if (acquired) {
                twoPermitsAcquired.countDown();
                if (!continueToDatabase.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("마지막 쿠폰 동시 요청 barrier가 시간 안에 해제되지 않았습니다.");
                }
            }
            return acquired;
        }).when(permitStore).tryAcquire(anyString(), anyInt());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<MvcResult>> requests = new ArrayList<>();
        try {
            for (int request = 0; request < 2; request++) {
                requests.add(workers.submit(() -> mockMvc.perform(post(ISSUE_PATH.formatted(lastCouponCampaign.getId()))
                    .header("X-USER-ID", FIRST_USER_ID)).andReturn()));
            }
            assertThat(twoPermitsAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            continueToDatabase.countDown();

            List<MvcResult> results = requests.stream().map(this::await).toList();
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 200);
            List<Long> issuedIds = results.stream().map(result -> {
                try {
                    return data(result).path("couponId").asLong();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
            assertThat(issuedIds).containsOnly(issuedIds.getFirst());
        } finally {
            continueToDatabase.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(coupons.countIssuedByCampaignId(lastCouponCampaign.getId())).isEqualTo(1);
        assertThat(campaigns.findById(lastCouponCampaign.getId()).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForSet().size(RedisCouponIssuePermits.PERMITS_KEY)).isZero();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private int issueStatus(Long userId) throws Exception {
        return mockMvc.perform(post(ISSUE_PATH.formatted(campaignId)).header("X-USER-ID", userId))
            .andReturn().getResponse().getStatus();
    }

    private String awaitToken(BlockingQueue<String> acquiredTokens) throws InterruptedException {
        String token = acquiredTokens.poll(5, TimeUnit.SECONDS);
        assertThat(token).isNotNull();
        return token;
    }

    private MvcResult await(Future<MvcResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("동시 쿠폰 요청이 제한 시간 안에 끝나지 않았습니다.", exception);
        }
    }
}
