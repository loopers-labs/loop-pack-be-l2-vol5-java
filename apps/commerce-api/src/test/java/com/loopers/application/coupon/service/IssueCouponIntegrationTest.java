package com.loopers.application.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponDatabaseClock;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.domain.coupon.exception.CouponIssueLockBusyException;
import com.loopers.domain.coupon.exception.DuplicateCouponIssueException;
import com.loopers.domain.coupon.model.Coupon;
import com.loopers.domain.coupon.model.CouponBenefit;
import com.loopers.domain.coupon.model.CouponCampaign;
import com.loopers.domain.coupon.model.CouponSlotState;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.infrastructure.persistence.coupon.jpa.CouponJpaRepository;
import com.loopers.infrastructure.persistence.coupon.jpa.CouponSlotJpaRepository;
import com.loopers.infrastructure.persistence.coupon.repository.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.persistence.coupon.repository.CouponRepositoryImpl;
import com.loopers.support.concurrency.ConcurrentRequests;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(IssueCouponIntegrationTest.ProbeConfiguration.class)
class IssueCouponIntegrationTest {
    @Autowired private IssueCouponService service;
    @Autowired private PrepareCouponCampaignService preparation;
    @Autowired private CouponDatabaseClock clock;
    @Autowired private CouponProbe coupons;
    @Autowired private CampaignProbe campaigns;
    @Autowired private CouponJpaRepository couponRows;
    @Autowired private CouponSlotJpaRepository slots;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void tearDown() {
        coupons.reset();
        campaigns.reset();
        cleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("DB 시각으로 발급하고 품절·비활성 뒤 중복 요청도 같은 ID·시각·혜택을 반환한다")
    void issuesAndReturnsStoredResultAfterBusinessClosure() {
        // arrange
        long campaignId = campaign(1, dbNow().plusSeconds(60));
        Instant before = dbNow();

        // act
        CouponIssueResult first = issue(campaignId, 1);
        preparation.execute(new CouponCampaignCommand.Activation(campaignId, false));
        CouponIssueResult repeat = issue(campaignId, 1);

        // assert
        assertThat(first.created()).isTrue();
        assertThat(repeat).isEqualTo(new CouponIssueResult(first.couponId(), campaignId, first.issuedAt(),
            first.benefitDescription(), first.discountAmount(), false));
        assertThat(first.issuedAt()).isBetween(before, dbNow());
        assertThat(first.benefitDescription()).isEqualTo("천 원 할인 🎟");
        assertThat(first.discountAmount()).isEqualTo(1_000);
        assertStoredCounts(campaignId, 1);
    }

    @Test
    @DisplayName("동시 발급에서 같은 사용자의 중복은 롤백하고 서로 다른 사용자의 발급 수는 모두 반영한다")
    void concurrentDuplicateRollsBackLosingSlotAndReadsCommittedResult() throws Exception {
        // arrange
        long campaignId = campaign(3, dbNow().plusSeconds(60));
        CyclicBarrier barrier = new CyclicBarrier(3);
        coupons.beforeSave = coupon -> await(barrier);

        // act
        var results = ConcurrentRequests.<CouponIssueResult>run(List.of(() -> issue(campaignId, 1), () -> issue(campaignId, 1),
            () -> issue(campaignId, 2)), 5, 12);

        // assert
        assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
        assertThat(results.get(0).value().couponId()).isEqualTo(results.get(1).value().couponId());
        assertThat(results.get(2).value().couponId()).isNotEqualTo(results.get(0).value().couponId());
        assertThat(results.stream().filter(result -> result.value().created()).count()).isEqualTo(2);
        assertThat(coupons.duplicates.get()).isEqualTo(1);
        assertStoredCounts(campaignId, 2);
        assertThat(slots.findAll()).filteredOn(slot -> slot.getState() == CouponSlotState.AVAILABLE).hasSize(1);
    }

    @Test
    @DisplayName("최초 중복 조회 직후 선행 발급이 마감 수량을 채워도 품절 대신 같은 저장 결과를 반환한다")
    void rechecksExistingAfterConcurrentBusinessRejection() throws Exception {
        // arrange
        long campaignId = campaign(1, dbNow().plusSeconds(60));
        CountDownLatch emptyRead = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        coupons.afterMissing = userId -> {
            if (firstRead.compareAndSet(true, false)) {
                emptyRead.countDown();
                await(winnerCommitted);
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<CouponIssueResult> follower = executor.submit(() -> issue(campaignId, 1));
        try {
            await(emptyRead);

            // act
            CouponIssueResult winner = issue(campaignId, 1);
            winnerCommitted.countDown();
            CouponIssueResult repeated = follower.get(8, TimeUnit.SECONDS);

            // assert
            assertThat(repeated).isEqualTo(new CouponIssueResult(winner.couponId(), campaignId, winner.issuedAt(),
                winner.benefitDescription(), winner.discountAmount(), false));
            assertStoredCounts(campaignId, 1);
        } finally {
            winnerCommitted.countDown();
            stop(executor);
        }
    }

    @Test
    @DisplayName("마지막 슬롯이 잠긴 동안은 일시 경합이고 실제 발급 완료 뒤 재요청은 품절이다")
    void distinguishesLockedLastSlotFromSoldOut() throws Exception {
        // arrange
        long campaignId = campaign(1, dbNow().plusSeconds(60));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        coupons.afterSave = coupon -> { inserted.countDown(); await(finish); };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<CouponIssueResult> issuing = executor.submit(() -> issue(campaignId, 1));
        try {
            await(inserted);

            // act / assert
            assertThatThrownBy(() -> issue(campaignId, 2)).isInstanceOf(CouponSlotBusyException.class);
            finish.countDown();
            assertThat(issuing.get(8, TimeUnit.SECONDS).created()).isTrue();
            coupons.afterSave = coupon -> {};
            assertDomainFailure(() -> issue(campaignId, 2), DomainErrorCode.COUPON_SOLD_OUT);
            assertStoredCounts(campaignId, 1);
        } finally {
            finish.countDown();
            stop(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"coupon", "counter"})
    @DisplayName("쿠폰 INSERT 직후 또는 마지막 발급 수 저장 직후 실패하면 쿠폰·슬롯·카운터가 모두 롤백된다")
    void rollsBackEveryStoredEffect(String failureAt) {
        // arrange
        long campaignId = campaign(1, dbNow().plusSeconds(60));
        if (failureAt.equals("coupon")) {
            coupons.afterSave = coupon -> { throw new IllegalStateException("실제 INSERT 뒤 실패"); };
        } else {
            campaigns.failAfterCounterSave = true;
        }

        // act / assert
        assertThatThrownBy(() -> issue(campaignId, 1)).isInstanceOf(IllegalStateException.class);
        assertStoredCounts(campaignId, 0);
        assertThat(slots.findAll()).allSatisfy(slot -> assertThat(slot.getState()).isEqualTo(CouponSlotState.AVAILABLE));
    }

    @Test
    @DisplayName("쿠폰 INSERT가 캠페인을 미리 잠그지 않으며 마지막 현재 행 검증에서 비활성이면 전체 롤백한다")
    void refreshesCampaignAndAllowsIndependentDeactivationBeforeFinalLock() {
        // arrange
        long campaignId = campaign(1, dbNow().plusSeconds(60));
        coupons.afterSave = coupon -> freshTransaction().executeWithoutResult(status -> {
            CouponCampaign current = campaigns.findByIdForIssue(campaignId).orElseThrow();
            current.deactivate();
            campaigns.save(current);
        });

        // act / assert
        assertDomainFailure(() -> issue(campaignId, 1), DomainErrorCode.COUPON_CAMPAIGN_INACTIVE);
        assertStoredCounts(campaignId, 0);
        assertThat(campaigns.findById(campaignId).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("잠금 SQL 진입은 마감 전이고 획득은 마감 후이면 새 DB 시각으로 거절하고 전체 롤백한다")
    void checksFreshDatabaseTimeAfterLockWait() throws Exception {
        // arrange
        Instant endsAt = dbNow().plusSeconds(4);
        long campaignId = campaign(1, endsAt);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch startLock = new CountDownLatch(1);
        CountDownLatch enteringLock = new CountDownLatch(1);
        AtomicReference<Instant> enteredAt = new AtomicReference<>();
        coupons.afterSave = coupon -> { inserted.countDown(); await(startLock); };
        campaigns.beforeIssueLock = () -> {
            enteredAt.set(clock.now());
            enteringLock.countDown();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> holder = executor.submit(() -> freshTransaction().executeWithoutResult(status -> {
            campaigns.findByIdForUpdate(campaignId).orElseThrow();
            locked.countDown();
            await(release);
        }));
        try {
            await(locked);
            Future<CouponIssueResult> issuing = executor.submit(() -> issue(campaignId, 1));
            await(inserted);

            // act: 실제 잠금 대기는 1초보다 짧게 유지하고 마감 경계를 가로지른다.
            waitForDatabaseTime(endsAt.minusMillis(400));
            startLock.countDown();
            await(enteringLock);
            assertThat(enteredAt.get()).isBefore(endsAt);
            assertThat(issuing.isDone()).isFalse();
            waitForDatabaseTime(endsAt.plusMillis(50));
            release.countDown();
            holder.get(8, TimeUnit.SECONDS);

            // assert
            assertThatThrownBy(() -> issuing.get(8, TimeUnit.SECONDS)).cause()
                .isInstanceOfSatisfying(DomainException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(DomainErrorCode.COUPON_CAMPAIGN_ENDED));
            assertStoredCounts(campaignId, 0);
        } finally {
            startLock.countDown();
            release.countDown();
            stop(executor);
        }
    }

    @Test
    @DisplayName("마지막 캠페인 잠금은 1초 제한으로 실패하고 같은 연결의 원래 설정과 모든 업무 상태를 복원한다")
    void limitsFinalLockWaitAndRestoresConnectionSetting() throws Exception {
        // arrange
        long campaignId = campaign(1, dbNow().plusSeconds(60));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> freshTransaction().executeWithoutResult(status -> {
            campaigns.findByIdForUpdate(campaignId).orElseThrow();
            locked.countDown();
            await(release);
        }));
        try {
            await(locked);

            // act / assert
            assertThatThrownBy(() -> issue(campaignId, 1)).isInstanceOf(CouponIssueLockBusyException.class);
            assertThat(campaigns.timeoutBefore.get()).isGreaterThan(1);
            assertThat(campaigns.timeoutAfter.get()).isEqualTo(campaigns.timeoutBefore.get());
            release.countDown();
            holder.get(8, TimeUnit.SECONDS);
            assertStoredCounts(campaignId, 0);
        } finally {
            release.countDown();
            stop(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-slot", "wrong-campaign", "same-slot"})
    @DisplayName("없는 슬롯·다른 캠페인·같은 슬롯 중복은 DB 제약 오류를 그대로 전파하고 사용자 중복으로 숨기지 않는다")
    void enforcesSlotRelationsAndDoesNotMisclassifyIntegrityErrors(String violation) {
        // arrange
        long campaignId = campaign(2, dbNow().plusSeconds(60));
        long otherCampaignId = campaign(1, dbNow().plusSeconds(60));
        long slotId = slots.findAll().stream().filter(slot -> slot.getCampaignId() == campaignId).findFirst().orElseThrow().getId();
        if (violation.equals("same-slot")) {
            freshTransaction().executeWithoutResult(status -> coupons.save(Coupon.create(campaignId, slotId, 1,
                new CouponBenefit("혜택", 1_000), clock.now())));
        }
        long selectedCampaign = violation.equals("wrong-campaign") ? otherCampaignId : campaignId;
        long selectedSlot = violation.equals("missing-slot") ? Long.MAX_VALUE : slotId;

        // act / assert
        assertThatThrownBy(() -> freshTransaction().executeWithoutResult(status -> coupons.save(Coupon.create(
            selectedCampaign, selectedSlot, 2, new CouponBenefit("혜택", 1_000), clock.now()))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(couponRows.count()).isEqualTo(violation.equals("same-slot") ? 1 : 0);
        assertThat(coupons.duplicates.get()).isZero();
    }

    @Test
    @DisplayName("DB 시각은 세션 시간대가 달라도 UTC epoch 마이크로초로 읽고 앱 시계를 사용하지 않는다")
    void readsDatabaseEpochRegardlessOfSessionTimeZone() {
        freshTransaction().executeWithoutResult(status -> {
            // arrange
            Object previous = entityManager.createNativeQuery("SELECT @@SESSION.time_zone").getSingleResult();
            Instant before = clock.now();
            try {
                entityManager.createNativeQuery("SET SESSION time_zone = '+09:00'").executeUpdate();

                // act
                Instant changedZone = clock.now();

                // assert
                assertThat(Duration.between(before, changedZone)).isBetween(Duration.ZERO, Duration.ofSeconds(3));
                assertThat(changedZone.getNano() % 1_000).isZero();
            } finally {
                entityManager.createNativeQuery("SET SESSION time_zone = :zone").setParameter("zone", previous).executeUpdate();
            }
        });
    }

    private CouponIssueResult issue(long campaignId, long userId) {
        return service.execute(new CouponIssueCommand(campaignId, userId));
    }

    private long campaign(int quota, Instant endsAt) {
        long id = preparation.execute(new CouponCampaignCommand.Create("발급 행사", quota, "천 원 할인 🎟", 1_000,
            dbNow().minusSeconds(60), endsAt)).campaignId();
        preparation.execute(new CouponCampaignCommand.Activation(id, true));
        return id;
    }

    private Instant dbNow() {
        return freshTransaction().execute(status -> clock.now());
    }

    private void waitForDatabaseTime(Instant target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (dbNow().isBefore(target)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("DB 시각 대기 타임아웃");
            }
            pause(10);
        }
    }

    private TransactionTemplate freshTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private void assertStoredCounts(long campaignId, long issuedCount) {
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(issuedCount);
        assertThat(couponRows.findAll()).filteredOn(coupon -> coupon.getCampaignId() == campaignId).hasSize((int) issuedCount);
        assertThat(slots.findAll()).filteredOn(slot -> slot.getCampaignId() == campaignId && slot.getState() == CouponSlotState.ISSUED)
            .hasSize((int) issuedCount);
    }

    private static void assertDomainFailure(Runnable action, DomainErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(errorCode));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 단계 대기 타임아웃");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(8, TimeUnit.SECONDS)) {
            throw new IllegalStateException("테스트 워커 종료 타임아웃");
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean @Primary
        CouponProbe couponProbe(CouponRepositoryImpl delegate) { return new CouponProbe(delegate); }

        @Bean @Primary
        CampaignProbe campaignProbe(CouponCampaignRepositoryImpl delegate, EntityManager entityManager) {
            return new CampaignProbe(delegate, entityManager);
        }
    }

    static class CouponProbe implements CouponRepository {
        private final CouponRepository delegate;
        private Consumer<Coupon> beforeSave = coupon -> {};
        private Consumer<Coupon> afterSave = coupon -> {};
        private Consumer<Long> afterMissing = userId -> {};
        private final AtomicInteger duplicates = new AtomicInteger();

        CouponProbe(CouponRepository delegate) { this.delegate = delegate; }

        void reset() {
            beforeSave = coupon -> {};
            afterSave = coupon -> {};
            afterMissing = userId -> {};
            duplicates.set(0);
        }

        @Override
        public Coupon save(Coupon coupon) {
            beforeSave.accept(coupon);
            try {
                Coupon saved = delegate.save(coupon);
                afterSave.accept(saved);
                return saved;
            } catch (DuplicateCouponIssueException failure) {
                duplicates.incrementAndGet();
                throw failure;
            }
        }

        @Override
        public Optional<Coupon> findByCampaignIdAndUserId(long campaignId, long userId) {
            Optional<Coupon> existing = delegate.findByCampaignIdAndUserId(campaignId, userId);
            if (existing.isEmpty()) {
                afterMissing.accept(userId);
            }
            return existing;
        }
    }

    static class CampaignProbe implements CouponCampaignRepository {
        private final CouponCampaignRepository delegate;
        private final EntityManager entityManager;
        private boolean failAfterCounterSave;
        private Runnable beforeIssueLock = () -> {};
        private final AtomicInteger timeoutBefore = new AtomicInteger();
        private final AtomicInteger timeoutAfter = new AtomicInteger();

        CampaignProbe(CouponCampaignRepository delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        void reset() {
            failAfterCounterSave = false;
            beforeIssueLock = () -> {};
            timeoutBefore.set(0);
            timeoutAfter.set(0);
        }

        @Override
        public CouponCampaign save(CouponCampaign campaign) {
            CouponCampaign saved = delegate.save(campaign);
            if (failAfterCounterSave && saved.getIssuedCount() > 0) {
                throw new IllegalStateException("실제 발급 수 저장 뒤 실패");
            }
            return saved;
        }

        @Override
        public Optional<CouponCampaign> findById(long campaignId) { return delegate.findById(campaignId); }

        @Override
        public Optional<CouponCampaign> findByIdForUpdate(long campaignId) { return delegate.findByIdForUpdate(campaignId); }

        @Override
        public Optional<CouponCampaign> findByIdForIssue(long campaignId) {
            timeoutBefore.set(timeout());
            beforeIssueLock.run();
            try {
                return delegate.findByIdForIssue(campaignId);
            } finally {
                timeoutAfter.set(timeout());
            }
        }

        private int timeout() {
            return ((Number) entityManager.createNativeQuery("SELECT @@SESSION.innodb_lock_wait_timeout").getSingleResult()).intValue();
        }
    }
}
