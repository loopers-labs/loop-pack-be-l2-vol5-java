package com.loopers.domain.coupon;

import com.loopers.concurrency.ConcurrentRunner;
import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 없이 CouponIssueService를 직접(동시에) 호출해 DB가 지키는 규칙만 확인한다.
 * 한도·중복·원자성은 모두 실제 MySQL의 조건부 UPDATE와 UNIQUE가 맡는다.
 */
@SpringBootTest
class CouponIssueIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private CouponIssueService issueService;

    @Autowired
    private CouponIssueTransactions transactions;

    @Autowired
    private CouponCampaignService campaignService;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private int issuedCount(Long campaignId) {
        return campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount();
    }

    @DisplayName("처음 발급하면, 쿠폰 1장이 생기고 발급 수가 1 오르며 created=true를 돌려준다.")
    @Test
    void issuesFirstCoupon() {
        // arrange
        Long campaignId = campaignService.create("신규", 5, "5,000원 할인권").getId();

        // act
        CouponIssueResult result = issueService.issue(campaignId, 1L);

        // assert
        assertThat(result.created()).isTrue();
        assertThat(couponJpaRepository.count()).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        CouponModel saved = couponJpaRepository.findById(result.couponId()).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getBenefitDescription()).isEqualTo("5,000원 할인권");
    }

    @DisplayName("같은 사용자가 다시 요청하면, 같은 couponId(created=false)를 받고 발급 수는 늘지 않는다.")
    @Test
    void returnsSameCoupon_whenSameUserRequestsAgain() {
        // arrange
        Long campaignId = campaignService.create("신규", 5, "혜택").getId();
        CouponIssueResult first = issueService.issue(campaignId, 1L);

        // act
        CouponIssueResult second = issueService.issue(campaignId, 1L);

        // assert
        assertThat(second.couponId()).isEqualTo(first.couponId());
        assertThat(second.created()).isFalse();
        assertThat(couponJpaRepository.count()).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("이미 받은 사용자는, 캠페인이 품절이거나 비활성이 된 뒤에도 같은 쿠폰을 받는다.")
    @Test
    void returnsExistingCoupon_evenAfterSoldOutOrDeactivated() {
        // arrange
        Long campaignId = campaignService.create("한 장", 1, "혜택").getId();
        CouponIssueResult first = issueService.issue(campaignId, 1L);
        CouponCampaignModel campaign = campaignJpaRepository.findById(campaignId).orElseThrow();
        campaign.deactivate();
        campaignJpaRepository.save(campaign);

        // act
        CouponIssueResult again = issueService.issue(campaignId, 1L);

        // assert
        assertThat(again.couponId()).isEqualTo(first.couponId());
        assertThat(again.created()).isFalse();
    }

    @DisplayName("없는 캠페인은 NOT_FOUND, 비활성 캠페인은 CAMPAIGN_INACTIVE로 거절하고 쓰기는 없다.")
    @Test
    void rejectsMissingAndInactiveCampaign() {
        // arrange
        CouponCampaignModel inactive = new CouponCampaignModel("끝난 이벤트", 5, "혜택");
        inactive.deactivate();
        Long inactiveId = campaignJpaRepository.save(inactive).getId();

        // act & assert
        assertThatThrownBy(() -> issueService.issue(9_999L, 1L))
            .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
        assertThatThrownBy(() -> issueService.issue(inactiveId, 1L))
            .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.CAMPAIGN_INACTIVE));
        assertThat(couponJpaRepository.count()).isZero();
        assertThat(issuedCount(inactiveId)).isZero();
    }

    @DisplayName("같은 사용자가 같은 순간 여러 번 요청해도, DB 효과는 쿠폰 1장·발급 수 1이고 모두 같은 couponId를 받는다.")
    @Test
    void sameUserConcurrently_hasSingleDbEffect() {
        // arrange
        Long campaignId = campaignService.create("신규", 100, "혜택").getId();
        List<Callable<CouponIssueResult>> tasks = IntStream.range(0, 8)
            .<Callable<CouponIssueResult>>mapToObj(i -> () -> issueService.issue(campaignId, 1L))
            .toList();

        // act
        List<WorkerResult<CouponIssueResult>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertThat(ConcurrentRunner.count(results, Outcome.SUCCESS)).as("모두 성공(201 또는 200)").isEqualTo(8);
        List<Long> couponIds = results.stream().map(r -> r.value().couponId()).distinct().toList();
        assertThat(couponIds).hasSize(1);
        assertThat(results.stream().filter(r -> r.value().created()).count()).as("새로 발급한 요청").isEqualTo(1);
        assertThat(couponJpaRepository.count()).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("총 5장에 서로 다른 8명이 동시에 요청하면, 발급 5·품절 3·쿠폰 5·발급 수 5이고 한도를 넘지 않는다.")
    @Test
    void fiveCoupons_eightUsers_concurrently() {
        // arrange
        Long campaignId = campaignService.create("한정 5장", 5, "혜택").getId();
        List<Callable<CouponIssueResult>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            long id = userId;
            tasks.add(() -> issueService.issue(campaignId, id));
        }

        // act
        List<WorkerResult<CouponIssueResult>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertThat(ConcurrentRunner.count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류는 업무 거절로 숨기지 않는다").isZero();
        assertThat(ConcurrentRunner.count(results, Outcome.SUCCESS)).isEqualTo(5);
        assertThat(ConcurrentRunner.count(results, Outcome.BUSINESS_REJECTED)).isEqualTo(3);
        assertThat(results.stream().filter(r -> r.outcome() == Outcome.BUSINESS_REJECTED))
            .allSatisfy(r -> assertThat(((CoreException) r.error()).getErrorType()).isEqualTo(ErrorType.COUPON_SOLD_OUT));
        assertThat(couponJpaRepository.count()).isEqualTo(5);
        assertThat(issuedCount(campaignId)).isEqualTo(5);
    }

    @DisplayName("마지막 한 장을 여러 명이 동시에 차지하려 해도, 한 명만 받고 거절된 요청의 쿠폰 insert는 남지 않는다.")
    @Test
    void lastCoupon_goesToExactlyOneUser() {
        // arrange
        Long campaignId = campaignService.create("마지막 1장", 1, "혜택").getId();
        List<Callable<CouponIssueResult>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            long id = userId;
            tasks.add(() -> issueService.issue(campaignId, id));
        }

        // act
        List<WorkerResult<CouponIssueResult>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertThat(ConcurrentRunner.count(results, Outcome.TECHNICAL_ERROR)).isZero();
        assertThat(ConcurrentRunner.count(results, Outcome.SUCCESS)).isEqualTo(1);
        assertThat(couponJpaRepository.count()).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("조건부 UPDATE가 실패하면, 같은 트랜잭션에서 한 INSERT까지 롤백된다.")
    @Test
    void rollsBackInsert_whenQuotaNotAcquired() {
        // arrange — 서비스의 빠른 거절을 거치지 않고 트랜잭션 단위를 직접 호출해, 이미 가득 찬 캠페인에서 UPDATE가 0행이 되게 한다
        Long campaignId = campaignService.create("가득 찬", 1, "혜택").getId();
        transactions.insertAndIncrease(campaignId, 1L, "혜택");

        // act & assert
        assertThatThrownBy(() -> transactions.insertAndIncrease(campaignId, 2L, "혜택"))
            .isInstanceOf(CouponIssueTransactions.QuotaNotAcquiredException.class);
        assertThat(couponJpaRepository.count()).as("두 번째 사용자의 insert는 롤백").isEqualTo(1);
        assertThat(couponJpaRepository.findAll()).extracting(CouponModel::getUserId).containsExactly(1L);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("INSERT가 DB 제약으로 실패하면, 쿠폰도 발급 수도 남지 않고 예외가 그대로 올라간다.")
    @Test
    void leavesNoWrites_whenInsertFailsByDbConstraint() {
        // arrange — 엔티티 검증은 통과하지만 컬럼 길이를 넘는 값으로 DB 제약 위반을 일으킨다
        Long campaignId = campaignService.create("긴 혜택", 5, "혜택").getId();
        String tooLong = "x".repeat(300);

        // act & assert
        assertThatThrownBy(() -> transactions.insertAndIncrease(campaignId, 1L, tooLong))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(couponJpaRepository.count()).isZero();
        assertThat(issuedCount(campaignId)).isZero();
    }
}
