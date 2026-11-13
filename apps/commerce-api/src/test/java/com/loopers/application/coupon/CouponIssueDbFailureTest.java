package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaignService;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisSemaphoreGate;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB 쪽 실제 실패(락 대기가 길어짐, 컬럼 제약 위반)에서 발급이 503으로 끝나고 자리가 반환되며 쓰기가 남지 않는지 확인한다.
 * 실패는 모두 실제 MySQL이 만든다(다른 트랜잭션이 캠페인 행의 락을 쥐거나, 쿠폰 컬럼 길이를 줄여 INSERT를 실패시킨다).
 */
@SpringBootTest
class CouponIssueDbFailureTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    private CouponIssueFacade issueFacade;

    @Autowired
    private CouponCampaignService campaignService;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("다른 트랜잭션이 캠페인 행의 락을 쥐고 있으면, 발급은 3초 안팎에 끝나 503이 되고 쿠폰 INSERT·발급 수·자리가 남지 않으며, 락이 풀리면 같은 요청이 성공한다.")
    @Test
    void returns503AndLeavesNothing_whenCampaignRowLockIsHeld() throws Exception {
        // arrange — 별도 트랜잭션이 캠페인 행을 FOR UPDATE로 잡고 놓지 않는다
        Long campaignId = campaignService.create("락 대기", 10, "혜택").getId();
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("select id from coupon_campaign where id = ?1 for update")
                .setParameter(1, campaignId).getResultList();
            locked.countDown();
            try {
                release.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }), executor);
        try {
            assertThat(locked.await(DEADLINE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // act
            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> issueFacade.issue(campaignId, 1L))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            // assert — MySQL 기본 락 대기(50초)가 아니라 3초 안팎에 끝난다. 쓰기는 모두 롤백, 자리는 반환
            assertThat(elapsed).as("트랜잭션 timeout 3초가 락 대기를 끊는다").isLessThan(Duration.ofSeconds(10));
            assertThat(gate.activeCount()).as("DB 장애여도 자리 반환").isZero();
            release.complete(null);
            holder.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
            assertThat(couponJpaRepository.count()).as("INSERT 롤백").isZero();
            assertThat(campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
        } finally {
            release.complete(null);
        }

        // act & assert — 락이 풀린 뒤 같은 사용자의 요청은 정상 발급된다
        assertThat(issueFacade.issue(campaignId, 1L).created()).isTrue();
    }

    @DisplayName("유일 제약이 아닌 DB 제약 위반으로 INSERT가 실패하면, 중복 성공으로 바꾸지 않고 503이며 쿠폰·발급 수·자리가 남지 않는다.")
    @Test
    void doesNotTurnNonUniqueConstraintFailureIntoDuplicate_returns503() {
        // arrange — 쿠폰 혜택 컬럼을 줄여 실제 "Data too long" 제약 위반을 일으키고, 끝나면 되돌린다
        Long campaignId = campaignService.create("제약 위반", 10, "5,000원 할인권").getId();
        alterCouponBenefitColumn(5);
        try {
            // act & assert
            assertThatThrownBy(() -> issueFacade.issue(campaignId, 1L))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
            assertThat(couponJpaRepository.count()).isZero();
            assertThat(campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
            assertThat(gate.activeCount()).isZero();
        } finally {
            alterCouponBenefitColumn(255);
        }
    }

    private void alterCouponBenefitColumn(int length) {
        transactionTemplate.executeWithoutResult(status ->
            entityManager.createNativeQuery("alter table coupon modify benefit_description varchar(" + length + ") not null").executeUpdate());
    }
}
