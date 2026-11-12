package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.coupon.RedisCouponIssuePermits;
import com.loopers.infrastructure.coupon.RedisCouponIssueQueue;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.time.MutableClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(CouponSeedServiceIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponSeedServiceIntegrationTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-11T00:00:00.123456Z");
    private static final String SEED_NAME = "local-coupon-seed";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock couponSeedClock() {
            return new MutableClock(FIRST_START, ZoneOffset.UTC);
        }
    }

    @Autowired private CouponSeedService seed;
    @Autowired private CouponCampaignRepository campaigns;
    @Autowired private CouponRepository coupons;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CouponIssueQueue queue;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;
    @PersistenceContext private EntityManager entityManager;

    private final List<Long> campaignIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setTime() {
        clock.set(FIRST_START);
        cleanUpQueueKeys();
    }

    @AfterEach
    void cleanUpOwnData() {
        cleanUpQueueKeys();
        for (Long campaignId : campaignIds) {
            jdbc.update("DELETE FROM coupons WHERE campaign_id = ?", campaignId);
            jdbc.update("DELETE FROM coupon_campaigns WHERE id = ?", campaignId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @DisplayName("첫 준비는 실행 시각부터 하루 동안 발급 가능한 캠페인과 미발급 100행을 같은 트랜잭션으로 만든다.")
    @Test
    void createsOneCampaignAndOneHundredRows() {
        CouponCampaign prepared = prepareSeed();
        CouponCampaign stored = transact(() -> campaigns.findById(prepared.getId()).orElseThrow());

        assertThat(stored.getName()).isEqualTo(SEED_NAME);
        assertThat(stored.getStartAt()).isEqualTo(FIRST_START);
        assertThat(stored.getEndAt()).isEqualTo(FIRST_START.plusSeconds(86400));
        assertThat(stored.getTotalQuota()).isEqualTo(100);
        assertThat(stored.getIssuedCount()).isZero();
        assertThat(stored.getTerminatedAt()).isNull();
        assertThat(stored.getBenefitDescription()).isEqualTo("5,000원 할인권");
        assertThat(transact(() -> coupons.countByCampaignId(stored.getId())).longValue()).isEqualTo(100);
        assertThat(transact(() -> coupons.countIssuedByCampaignId(stored.getId())).longValue()).isZero();
    }

    @DisplayName("기본 재실행은 만료 뒤에도 기존 캠페인 기간·발급 결과·쿠폰 ID를 보존한다.")
    @Test
    void preservesExistingSeedWithoutReset() {
        CouponCampaign campaign = prepareSeed();
        long userId = createUser();
        Coupon issued = issueOne(campaign.getId(), userId, FIRST_START.plusSeconds(1));
        List<Long> originalIds = stockIds(campaign.getId());
        clock.set(FIRST_START.plusSeconds(172800));

        CouponCampaign preparedAgain = seed.prepare(false);
        CouponCampaign stored = transact(() -> campaigns.findById(campaign.getId()).orElseThrow());
        Coupon storedCoupon = transact(() -> coupons.findById(issued.getId()).orElseThrow());

        assertThat(preparedAgain.getId()).isEqualTo(campaign.getId());
        assertThat(stockIds(campaign.getId())).containsExactlyElementsOf(originalIds);
        assertThat(stored.getIssuedCount()).isEqualTo(1);
        assertThat(stored.getStartAt()).isEqualTo(FIRST_START);
        assertThat(stored.getEndAt()).isEqualTo(campaign.getEndAt());
        assertThat(storedCoupon.getUserId()).isEqualTo(userId);
        assertThat(storedCoupon.getIssuedAt()).isEqualTo(FIRST_START.plusSeconds(1));
    }

    @DisplayName("명시적 reset은 품절 seed의 같은 100개 ID를 유지하며 기간·카운터·종료·할당을 함께 복원한다.")
    @Test
    void resetsOnlySeedAndPreservesStockIds() {
        CouponCampaign campaign = prepareSeed();
        List<Long> originalIds = stockIds(campaign.getId());
        for (int index = 0; index < 100; index++) {
            issueOne(campaign.getId(), createUser(), FIRST_START.plusSeconds(1));
        }
        assertThat(transact(() -> campaigns.findById(campaign.getId()).orElseThrow()).getTerminatedAt()).isNotNull();
        CouponCampaign unrelated = createUnrelatedCampaign("별도 행사");
        List<Coupon> unrelatedStock = transact(() -> coupons.saveStock(List.of(Coupon.create(unrelated.getId()))));
        Coupon unrelatedCoupon = issueOne(unrelated.getId(), createUser(), FIRST_START.plusSeconds(2));
        Instant resetTime = FIRST_START.plusSeconds(172800);
        clock.set(resetTime);

        seed.prepare(true);

        CouponCampaign reset = transact(() -> campaigns.findById(campaign.getId()).orElseThrow());
        assertThat(reset.getIssuedCount()).isZero();
        assertThat(reset.getTerminatedAt()).isNull();
        assertThat(reset.getStartAt()).isEqualTo(resetTime);
        assertThat(reset.getEndAt()).isEqualTo(resetTime.plusSeconds(86400));
        assertThat(stockIds(campaign.getId())).containsExactlyElementsOf(originalIds);
        for (Long couponId : originalIds) {
            Coupon coupon = transact(() -> coupons.findById(couponId).orElseThrow());
            assertThat(coupon.getUserId()).isNull();
            assertThat(coupon.getIssuedAt()).isNull();
        }
        assertThat(transact(() -> coupons.countIssuedByCampaignId(campaign.getId())).longValue()).isZero();
        assertThat(transact(() -> coupons.findById(unrelatedStock.getFirst().getId()).orElseThrow()).getUserId())
            .isEqualTo(unrelatedCoupon.getUserId());
        assertThat(transact(() -> campaigns.findById(unrelated.getId()).orElseThrow()).getIssuedCount()).isEqualTo(1);
    }

    @DisplayName("reset은 seed 캠페인의 완료된 대기열 작업과 중복 접수 정보를 지운 뒤 DB를 초기화한다.")
    @Test
    void clearsCompletedQueueRequestsOnReset() {
        CouponCampaign campaign = prepareSeed();
        CouponIssueRequest completed = completedQueueRequest(campaign.getId(), 77001L);

        seed.prepare(true);

        assertThat(queue.findById(completed.getRequestId())).isEmpty();
        assertThat(queue.findByCampaignIdAndUserId(campaign.getId(), 77001L)).isEmpty();
        CouponIssueRequest again = CouponIssueRequest.create(campaign.getId(), 77001L, FIRST_START);
        assertThat(queue.submit(again, 20)).isInstanceOf(CouponIssueQueueSubmission.Created.class);
        assertThat(again.getRequestId()).isNotEqualTo(completed.getRequestId());
    }

    @DisplayName("seed 캠페인의 미완료 대기열 작업이 남아 있으면 reset을 거절하고 DB와 대기열을 그대로 둔다.")
    @Test
    void rejectsResetWhenUnfinishedQueueRequestRemains() {
        CouponCampaign campaign = prepareSeed();
        issueOne(campaign.getId(), createUser(), FIRST_START.plusSeconds(1));
        CouponIssueRequest waiting = CouponIssueRequest.create(campaign.getId(), 77002L, FIRST_START);
        queue.submit(waiting, 20);

        assertThatThrownBy(() -> seed.prepare(true)).isInstanceOfSatisfying(CoreException.class,
            error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));

        assertThat(transact(() -> campaigns.findById(campaign.getId()).orElseThrow()).getIssuedCount()).isEqualTo(1);
        assertThat(queue.findById(waiting.getRequestId())).isPresent();
    }

    @DisplayName("reset 없이 준비하면 대기열 정보를 건드리지 않는다.")
    @Test
    void keepsQueueRequestsWithoutReset() {
        CouponCampaign campaign = prepareSeed();
        CouponIssueRequest completed = completedQueueRequest(campaign.getId(), 77003L);

        seed.prepare(false);

        assertThat(queue.findById(completed.getRequestId())).isPresent();
    }

    @DisplayName("재고 행 수가 한도와 다르면 자동 보충·reset하지 않고 기존 발급 결과를 보존한 채 실패한다.")
    @Test
    void rejectsStockCountMismatchBeforeReset() {
        CouponCampaign campaign = prepareSeed();
        Coupon issued = issueOne(campaign.getId(), createUser(), FIRST_START.plusSeconds(1));
        long missingId = transact(() -> coupons.findFirstUnissuedId(campaign.getId()).orElseThrow());
        jdbc.update("DELETE FROM coupons WHERE id = ?", missingId);
        clock.set(FIRST_START.plusSeconds(172800));

        assertThatThrownBy(() -> seed.prepare(true)).isInstanceOfSatisfying(CoreException.class,
            failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));

        CouponCampaign stored = transact(() -> campaigns.findById(campaign.getId()).orElseThrow());
        assertThat(stored.getIssuedCount()).isEqualTo(1);
        assertThat(stored.getStartAt()).isEqualTo(FIRST_START);
        assertThat(transact(() -> coupons.countByCampaignId(campaign.getId())).longValue()).isEqualTo(99);
        assertThat(transact(() -> coupons.findById(issued.getId()).orElseThrow()).getUserId()).isEqualTo(issued.getUserId());
    }

    @DisplayName("예약된 seed 이름이 중복되면 임의의 행을 선택해 초기화하지 않는다.")
    @Test
    void rejectsAmbiguousSeedName() {
        CouponCampaign original = prepareSeed();
        createUnrelatedCampaign(SEED_NAME);
        clock.set(FIRST_START.plusSeconds(172800));

        assertThatThrownBy(() -> seed.prepare(true)).isInstanceOfSatisfying(CoreException.class,
            failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));

        assertThat(transact(() -> campaigns.findById(original.getId()).orElseThrow()).getStartAt()).isEqualTo(FIRST_START);
        assertThat(stockIds(original.getId())).hasSize(100);
    }

    private CouponCampaign prepareSeed() {
        CouponCampaign prepared = seed.prepare(false);
        campaignIds.add(prepared.getId());
        return prepared;
    }

    private CouponCampaign createUnrelatedCampaign(String name) {
        CouponCampaign campaign = transact(() -> campaigns.save(CouponCampaign.create(
            name, 1, "5,000원 할인권", FIRST_START, FIRST_START.plusSeconds(86400))));
        campaignIds.add(campaign.getId());
        return campaign;
    }

    private long createUser() {
        long id = transact(() -> users.save(User.create())).getId();
        userIds.add(id);
        return id;
    }

    private Coupon issueOne(Long campaignId, long userId, Instant issueNow) {
        return transact(() -> {
            assertThat(campaigns.incrementIssuedCountIfIssuable(campaignId, issueNow)).isEqualTo(1);
            long couponId = coupons.findFirstUnissuedId(campaignId).orElseThrow();
            assertThat(coupons.assignIfUnissued(couponId, campaignId, userId, issueNow)).isEqualTo(1);
            return coupons.findById(couponId).orElseThrow();
        });
    }

    private List<Long> stockIds(Long campaignId) {
        return jdbc.queryForList("SELECT id FROM coupons WHERE campaign_id = ? ORDER BY id", Long.class, campaignId);
    }

    private <T> T transact(Supplier<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            T result = action.get();
            entityManager.flush();
            entityManager.clear();
            return result;
        });
    }

    private CouponIssueRequest completedQueueRequest(Long campaignId, Long userId) {
        CouponIssueRequest request = CouponIssueRequest.create(campaignId, userId, FIRST_START);
        queue.submit(request, 20);
        queue.claim("seed-test-token-" + userId);
        queue.recordSucceeded(request.getRequestId(), "seed-test-token-" + userId, userId);
        return request;
    }

    private void cleanUpQueueKeys() {
        redisTemplate.delete(List.of(RedisCouponIssuePermits.PERMITS_KEY, RedisCouponIssueQueue.WAITING_KEY,
            RedisCouponIssueQueue.PROCESSING_KEY, RedisCouponIssueQueue.JOBS_KEY, RedisCouponIssueQueue.REQUESTS_KEY));
    }
}
