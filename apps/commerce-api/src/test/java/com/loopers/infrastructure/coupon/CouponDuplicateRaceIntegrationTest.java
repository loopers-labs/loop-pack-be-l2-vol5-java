package com.loopers.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import com.loopers.application.coupon.CouponIssuanceTransactions;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAlreadyIssuedException;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.ConcurrentRequests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "coupon.queue.worker.enabled=false"})
@Import(CouponDuplicateRaceIntegrationTest.RaceConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponDuplicateRaceIntegrationTest {
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private CouponJpaRepository couponRows;
    @Autowired private CouponIssuanceTransactions issuance;
    @Autowired private RaceCouponRepository coupons;
    private final List<Long> createdCampaigns = new ArrayList<>();

    @Test
    void 같은_사용자의_두_사전_조회가_비어_있어도_유일_제약_충돌을_롤백하고_같은_ID를_반환한다()
            throws Exception {
        long campaignId = createCampaign(5);
        long userId = 301;
        coupons.holdTwoEmptyReads(campaignId, userId);
        var firstCompletion = new CouponIssuanceTransactions.Completion();
        var secondCompletion = new CouponIssuanceTransactions.Completion();
        var results = ConcurrentRequests.<CouponIssuanceTransactions.IssueResult>run(List.of(
                () -> issuance.issue(campaignId, userId, firstCompletion),
                () -> issuance.issue(campaignId, userId, secondCompletion)));

        assertThat(results.failures()).isEmpty();
        assertThat(results.successes()).hasSize(2);
        long couponId = coupons.findByCampaignAndUser(campaignId, userId).orElseThrow().couponId();
        assertThat(results.successes()).extracting(result -> result.coupon().couponId()).containsOnly(couponId);
        assertThat(results.successes().stream().filter(CouponIssuanceTransactions.IssueResult::created)).hasSize(1);
        assertThat(coupons.duplicateConflicts()).isEqualTo(1);
        assertThat(List.of(firstCompletion.outcome(), secondCompletion.outcome()))
                .containsExactlyInAnyOrder(CouponIssuanceTransactions.Outcome.COMMITTED,
                        CouponIssuanceTransactions.Outcome.ROLLED_BACK);
        assertThat(couponRows.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
    }

    @Test
    void 마지막_한_장을_같은_사용자가_경쟁해도_두_요청은_같은_쿠폰_ID를_받는다() throws Exception {
        long campaignId = createCampaign(1);
        long userId = 302;
        coupons.holdTwoEmptyReads(campaignId, userId);
        var results = ConcurrentRequests.<CouponIssuanceTransactions.IssueResult>run(List.of(
                () -> issuance.issue(campaignId, userId, new CouponIssuanceTransactions.Completion()),
                () -> issuance.issue(campaignId, userId, new CouponIssuanceTransactions.Completion())));

        assertThat(results.failures()).isEmpty();
        assertThat(results.successes()).hasSize(2);
        long couponId = coupons.findByCampaignAndUser(campaignId, userId).orElseThrow().couponId();
        assertThat(results.successes()).extracting(result -> result.coupon().couponId()).containsOnly(couponId);
        assertThat(results.successes().stream().filter(CouponIssuanceTransactions.IssueResult::created)).hasSize(1);
        assertThat(couponRows.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
    }

    @Test
    void 중복_충돌_롤백_뒤_복구_조회가_실패하면_결과를_확정하지_않고_재시도하지_않는다() throws Exception {
        long campaignId = createCampaign(5);
        long userId = 303;
        coupons.holdTwoEmptyReads(campaignId, userId);
        coupons.failRecoveryRead();
        var firstCompletion = new CouponIssuanceTransactions.Completion();
        var secondCompletion = new CouponIssuanceTransactions.Completion();

        var results = ConcurrentRequests.<CouponIssuanceTransactions.IssueResult>run(List.of(
                () -> issuance.issue(campaignId, userId, firstCompletion),
                () -> issuance.issue(campaignId, userId, secondCompletion)));

        assertThat(results.successes()).hasSize(1);
        assertThat(results.failures()).hasSize(1).allSatisfy(error ->
                assertThat(error).isInstanceOf(IllegalStateException.class).hasMessage("recovery read failed"));
        assertThat(coupons.duplicateConflicts()).isEqualTo(1);
        var completions = List.of(firstCompletion, secondCompletion);
        var failed = completions.stream().filter(completion ->
                completion.outcome() == CouponIssuanceTransactions.Outcome.ROLLED_BACK).findFirst().orElseThrow();
        assertThat(failed.safeToRetry()).isFalse();
        assertThat(couponRows.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
    }

    private long createCampaign(long quota) {
        var campaign = campaigns.saveAndFlush(new CouponCampaignJpaEntity("중복 경쟁", true, quota, "할인권"));
        createdCampaigns.add(campaign.getId());
        return campaign.getId();
    }

    @AfterEach
    void cleanUpOnlyCreatedCampaigns() {
        coupons.disableBarrier();
        for (long campaignId : createdCampaigns) {
            couponRows.deleteByCampaignId(campaignId);
            campaigns.deleteById(campaignId);
        }
    }

    @TestConfiguration
    static class RaceConfiguration {
        @Bean
        @Primary
        RaceCouponRepository raceCouponRepository(CouponRepositoryImpl delegate) {
            return new RaceCouponRepository(delegate);
        }
    }

    /** 실제 빈 사전 조회 2건이 완료될 때까지 서로 기다리게 하여 경쟁을 재현한다. */
    static class RaceCouponRepository implements CouponRepository {
        private final CouponRepositoryImpl delegate;
        private final AtomicLong campaign = new AtomicLong(-1);
        private final AtomicLong user = new AtomicLong(-1);
        private final AtomicInteger readsRemaining = new AtomicInteger();
        private final AtomicInteger duplicates = new AtomicInteger();
        private final AtomicBoolean failRecovery = new AtomicBoolean();
        private volatile CountDownLatch emptyReads = new CountDownLatch(0);

        RaceCouponRepository(CouponRepositoryImpl delegate) {
            this.delegate = delegate;
        }

        void holdTwoEmptyReads(long campaignId, long userId) {
            campaign.set(campaignId);
            user.set(userId);
            duplicates.set(0);
            failRecovery.set(false);
            emptyReads = new CountDownLatch(2);
            readsRemaining.set(2);
        }

        void disableBarrier() {
            campaign.set(-1);
            readsRemaining.set(0);
            failRecovery.set(false);
            while (emptyReads.getCount() > 0) {
                emptyReads.countDown();
            }
        }

        int duplicateConflicts() {
            return duplicates.get();
        }

        void failRecoveryRead() {
            failRecovery.set(true);
        }

        @Override
        public Optional<Coupon> findByCampaignAndUser(long campaignId, long userId) {
            if (campaign.get() == campaignId && user.get() == userId && failRecovery.get()
                    && duplicates.get() > 0 && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("recovery read failed");
            }
            var result = delegate.findByCampaignAndUser(campaignId, userId);
            if (campaign.get() == campaignId && user.get() == userId && result.isEmpty()
                    && (!TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                    && readsRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                emptyReads.countDown();
                try {
                    if (!emptyReads.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("두 사전 조회를 확인하지 못했습니다");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("사전 조회 경쟁이 중단됐습니다", interrupted);
                }
            }
            return result;
        }

        @Override
        public List<Coupon> findByUser(long userId) {
            return delegate.findByUser(userId);
        }

        @Override
        public Coupon insert(long campaignId, long userId, String benefitDescription) {
            try {
                return delegate.insert(campaignId, userId, benefitDescription);
            } catch (CouponAlreadyIssuedException duplicate) {
                duplicates.incrementAndGet();
                throw duplicate;
            }
        }
    }
}
