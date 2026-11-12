package com.loopers.domain.coupon;

import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.ConcurrentRunner;
import com.loopers.utils.ConcurrentRunner.Run;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 8주차 설계 §5-3 DB 구간. Redis 없이 DB 계층만으로 총량(INV-W8-02)·사용자당 1장(INV-W8-03)·원자적 롤백(INV-W8-08)이 성립함을 증명한다.
 * 데이터는 worker 시작 전에 commit 되고, 각 worker 는 독립 트랜잭션으로 실제 빈을 호출한다. 클래스 단위 @Transactional 을 쓰지 않는다.
 */
@SpringBootTest
class CouponIssueServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SOLD_OUT_MESSAGE = "쿠폰이 모두 소진되었습니다.";
    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));

    @MockitoSpyBean
    private CouponRepository couponRepository;

    private final CouponIssueService couponIssueService;
    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponJpaRepository couponJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public CouponIssueServiceTest(
        CouponIssueService couponIssueService,
        CouponCampaignRepository couponCampaignRepository,
        CouponJpaRepository couponJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.couponIssueService = couponIssueService;
        this.couponCampaignRepository = couponCampaignRepository;
        this.couponJpaRepository = couponJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long saveCampaign(int totalQuota) {
        return couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", totalQuota, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT)).getId();
    }

    private int issuedCountOf(Long campaignId) {
        return couponCampaignRepository.findById(campaignId).orElseThrow().getIssuedCount();
    }

    private static boolean isSoldOut(Run<?> run) {
        return run.error() instanceof CoreException e
            && e.getErrorType() == ErrorType.CONFLICT
            && SOLD_OUT_MESSAGE.equals(e.getMessage());
    }

    private static void assertNoTechnicalError(List<? extends Run<?>> runs) {
        List<Throwable> technical = runs.stream()
            .filter(Run::failed)
            .filter(run -> !isSoldOut(run))
            .map(Run::error)
            .toList();
        assertThat(technical).as("업무 결과(품절) 외의 예외가 없어야 한다").isEmpty();
    }

    @DisplayName("INV-W8-02 총량. ")
    @Nested
    class TotalQuota {
        @DisplayName("총량 5 에 서로 다른 8명이 동시에 발급하면, 발급 5·품절 3 이고 issued_count 와 쿠폰 row 는 5 다.")
        @Test
        void issuesExactlyTotalQuota_whenEightUsersCompete() throws InterruptedException {
            Long campaignId = saveCampaign(5);
            List<Callable<CouponIssueService.IssueResult>> tasks = new ArrayList<>();
            for (long userId = 1; userId <= 8; userId++) {
                long id = userId;
                tasks.add(() -> couponIssueService.issue(campaignId, id));
            }

            List<Run<CouponIssueService.IssueResult>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            assertNoTechnicalError(runs);
            long created = runs.stream().filter(run -> !run.failed() && run.value().created()).count();
            long soldOut = runs.stream().filter(CouponIssueServiceTest::isSoldOut).count();
            assertThat(created).isEqualTo(5);
            assertThat(soldOut).isEqualTo(3);
            assertThat(issuedCountOf(campaignId)).isEqualTo(5);
            assertThat(couponJpaRepository.count()).isEqualTo(5);
        }
    }

    @DisplayName("INV-W8-03 사용자당 1장. ")
    @Nested
    class OnePerUser {
        @DisplayName("같은 사용자의 발급 2건이 동시에 도착하면, 쿠폰 1건·두 응답의 couponId 동일·issued_count 증가 1 이다.")
        @Test
        void issuesOnce_whenSameUserRequestsConcurrently() throws InterruptedException {
            Long campaignId = saveCampaign(5);
            List<Callable<CouponIssueService.IssueResult>> tasks = List.of(
                () -> couponIssueService.issue(campaignId, 1L),
                () -> couponIssueService.issue(campaignId, 1L)
            );

            List<Run<CouponIssueService.IssueResult>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            assertNoTechnicalError(runs);
            assertThat(runs).allMatch(run -> !run.failed());
            Long firstId = runs.get(0).value().coupon().getId();
            Long secondId = runs.get(1).value().coupon().getId();
            assertThat(firstId).isEqualTo(secondId);
            assertThat(runs.stream().filter(run -> run.value().created()).count()).isEqualTo(1);
            assertThat(issuedCountOf(campaignId)).isEqualTo(1);
            assertThat(couponJpaRepository.count()).isEqualTo(1);
        }

        /**
         * "조회했을 때 없었다 → INSERT 에서 UNIQUE 위반" 경로를 결정적으로 밟는다 (설계 §6 마지막 안전장치, C5).
         * 트랜잭션 안의 첫 조회만 비어 있게 만들어 INSERT 가 반드시 제약에 부딪히게 하고, 복구 조회는 실제 메서드로 돌린다.
         */
        @DisplayName("조회 시점엔 없었지만 insert 에서 UNIQUE 에 걸리면, 기존 쿠폰을 돌려주고 올렸던 issued_count 는 롤백된다.")
        @Test
        void returnsExisting_andRollsBackIncrement_whenInsertHitsUnique() {
            Long campaignId = saveCampaign(5);
            Coupon existing = couponIssueService.issue(campaignId, 1L).coupon();
            AtomicInteger calls = new AtomicInteger();
            doAnswer(invocation -> calls.getAndIncrement() == 0 ? Optional.empty() : invocation.callRealMethod())
                .when(couponRepository).findByCampaignIdAndUserId(campaignId, 1L);

            CouponIssueService.IssueResult result = couponIssueService.issue(campaignId, 1L);

            assertThat(result.created()).isFalse();
            assertThat(result.coupon().getId()).isEqualTo(existing.getId());
            assertThat(calls.get()).as("TX 안 조회 1회 + 복구 조회 1회").isEqualTo(2);
            assertThat(issuedCountOf(campaignId)).isEqualTo(1);
            assertThat(couponJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("이미 발급받은 사용자가 다시 요청하면, 같은 쿠폰을 created=false 로 돌려주고 issued_count 는 그대로다.")
        @Test
        void returnsExisting_whenUserAlreadyIssued() {
            Long campaignId = saveCampaign(5);
            CouponIssueService.IssueResult first = couponIssueService.issue(campaignId, 1L);

            CouponIssueService.IssueResult second = couponIssueService.issue(campaignId, 1L);

            assertThat(first.created()).isTrue();
            assertThat(second.created()).isFalse();
            assertThat(second.coupon().getId()).isEqualTo(first.coupon().getId());
            assertThat(issuedCountOf(campaignId)).isEqualTo(1);
        }
    }

    @DisplayName("INV-W8-08 원자적 롤백. ")
    @Nested
    class AtomicRollback {
        @DisplayName("issued_count 증가 후 쿠폰 insert 가 실패하면, 예외가 전파되고 issued_count 와 쿠폰 row 모두 0 으로 돌아간다.")
        @Test
        void rollsBackIncrement_whenInsertFails() {
            Long campaignId = saveCampaign(5);
            doThrow(new IllegalStateException("테스트: 쿠폰 저장 실패")).when(couponRepository).save(any(Coupon.class));

            assertThatThrownBy(() -> couponIssueService.issue(campaignId, 1L))
                .isInstanceOf(IllegalStateException.class);

            assertThat(issuedCountOf(campaignId)).isZero();
            assertThat(couponJpaRepository.count()).isZero();
        }
    }

    @DisplayName("캠페인 상태. ")
    @Nested
    class CampaignState {
        @DisplayName("없는 캠페인이면 NOT_FOUND 다.")
        @Test
        void throwsNotFound_whenCampaignMissing() {
            assertThatThrownBy(() -> couponIssueService.issue(999L, 1L))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("종료된 캠페인이면 CONFLICT 이고 쿠폰은 만들어지지 않는다.")
        @Test
        void throwsConflict_whenCampaignClosed() {
            CouponCampaign campaign = new CouponCampaign("종료 캠페인", 5, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT);
            campaign.close();
            Long campaignId = couponCampaignRepository.save(campaign).getId();

            assertThatThrownBy(() -> couponIssueService.issue(campaignId, 1L))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.CONFLICT);
            assertThat(couponJpaRepository.count()).isZero();
        }
    }
}
