package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueResult.Outcome;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.IssuedCoupon;
import com.loopers.domain.coupon.IssuedCouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * DB 발급 경로를 실제 MySQL로 본다(docs/week8/decisions.md INV-801, INV-802, INV-806, D-8, D-15, D-16).
 * Redis 자리는 이번 범위 밖이다 — {@link CouponIssuer}를 직접 부른다.
 *
 * <p>테스트 메서드에 @Transactional을 붙이지 않는다 — 테스트 자동 rollback을 발급 TX rollback으로 오인하지 않기 위해서다.
 * 결과는 모든 실행이 끝난 뒤 새로 읽은 DB(issued_count, issued_coupon 행 수)와 대조한다.
 */
@SpringBootTest
class CouponIssueIntegrationTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private CouponIssuer couponIssuer;

    @Autowired
    private CouponQuery couponQuery;

    @Autowired
    private CouponCampaignSetup couponCampaignSetup;

    @MockitoSpyBean
    private IssuedCouponRepository issuedCouponRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long campaign(int totalQuota) {
        return couponCampaignSetup.create("오픈 기념", totalQuota, "5,000원 할인권", 5_000L).campaignId();
    }

    @DisplayName("한 사용자가 발급할 때,")
    @Nested
    class SingleUser {
        @DisplayName("처음이면, CREATED이고 쿠폰 1장·발급 수 1이며, 혜택 사본이 저장되고 보유 목록에 같은 couponId로 보인다.")
        @Test
        void firstIssue_createsOneCoupon() {
            Long campaignId = campaign(10);

            CouponIssueResult result = couponIssuer.issue(campaignId, 7L);

            List<IssuedCouponInfo> mine = couponQuery.listMine(7L);
            assertAll(
                () -> assertThat(result.outcome()).isEqualTo(Outcome.CREATED),
                () -> assertThat(result.coupon().campaignId()).isEqualTo(campaignId),
                () -> assertThat(result.coupon().userId()).isEqualTo(7L),
                () -> assertThat(result.coupon().benefitDescription()).isEqualTo("5,000원 할인권"),
                () -> assertThat(result.coupon().discountAmount()).isEqualTo(5_000L),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(couponRows(campaignId)).isEqualTo(1L),
                () -> assertThat(mine).containsExactly(result.coupon()),
                () -> assertThat(jdbcTemplate.queryForObject(
                    "SELECT benefit_description FROM issued_coupon WHERE id = ?", String.class, result.coupon().couponId()))
                    .isEqualTo("5,000원 할인권")
            );
        }

        @DisplayName("다시 요청하면, EXISTING이고 같은 couponId이며 발급 수는 오르지 않는다.")
        @Test
        void repeat_returnsSameCouponWithoutIncrement() {
            Long campaignId = campaign(10);
            CouponIssueResult first = couponIssuer.issue(campaignId, 7L);

            CouponIssueResult second = couponIssuer.issue(campaignId, 7L);

            assertAll(
                () -> assertThat(second.outcome()).isEqualTo(Outcome.EXISTING),
                () -> assertThat(second.coupon()).isEqualTo(first.coupon()),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(couponRows(campaignId)).isEqualTo(1L)
            );
        }

        @DisplayName("없는 캠페인이면, NOT_FOUND이고 쿠폰이 없다.")
        @Test
        void missingCampaign_isNotFound() {
            CoreException exception = assertThrows(CoreException.class, () -> couponIssuer.issue(999L, 7L));

            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                () -> assertThat(couponQuery.listMine(7L)).isEmpty()
            );
        }
    }

    @DisplayName("같은 사용자가 5번 동시에 발급하면, 쿠폰은 1장·발급 수 1이고 모든 결과가 같은 couponId다. 다른 사용자 목록에는 없다.")
    @Test
    void concurrentSameUser_getsExactlyOneCoupon() throws Exception {
        Long campaignId = campaign(10);
        int threads = 5;

        List<Callable<CouponIssueResult>> requests = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            requests.add(() -> couponIssuer.issue(campaignId, 7L));
        }
        List<Attempt> outcomes = runConcurrently(requests);

        List<CouponIssueResult> results = outcomes.stream().map(Attempt::result).toList();
        assertAll(
            () -> assertThat(outcomes).as("실행 수").hasSize(threads),
            () -> assertThat(outcomes).as("예외: %s", errorsOf(outcomes)).allMatch(o -> o.error() == null),
            () -> assertThat(results).extracting(r -> r.coupon().couponId()).containsOnly(results.get(0).coupon().couponId()),
            () -> assertThat(results).filteredOn(r -> r.outcome() == Outcome.CREATED).hasSize(1),
            () -> assertThat(results).filteredOn(r -> r.outcome() == Outcome.EXISTING).hasSize(threads - 1),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(couponRows(campaignId)).isEqualTo(1L),
            () -> assertThat(couponQuery.listMine(7L)).hasSize(1),
            () -> assertThat(couponQuery.listMine(8L)).isEmpty()
        );
    }

    @DisplayName("총 5장에 서로 다른 8명이 동시에 발급하면, 발급 5·품절 3·기타 0이고 실제 쿠폰 5장·발급 수 5다. (INV-801, D-16)")
    @Test
    void lastCouponRace_neverExceedsQuota() throws Exception {
        Long campaignId = campaign(5);
        int users = 8;

        List<Callable<CouponIssueResult>> requests = new ArrayList<>();
        for (long userId = 1; userId <= users; userId++) {
            long requester = userId;
            requests.add(() -> couponIssuer.issue(campaignId, requester));
        }
        List<Attempt> outcomes = runConcurrently(requests);

        long created = outcomes.stream()
            .filter(o -> o.result() != null && o.result().outcome() == Outcome.CREATED).count();
        long soldOut = outcomes.stream()
            .filter(o -> o.error() instanceof CoreException e
                && e.getErrorType() == ErrorType.CONFLICT && CouponCampaign.SOLD_OUT_MESSAGE.equals(e.getMessage()))
            .count();
        long other = outcomes.size() - created - soldOut;
        assertAll(
            () -> assertThat(outcomes).as("실행 수").hasSize(users),
            () -> assertThat(created).isEqualTo(5),
            () -> assertThat(soldOut).isEqualTo(3),
            () -> assertThat(other).as("기타 결과: %s", errorsOf(outcomes)).isZero(),
            () -> assertThat(couponRows(campaignId)).isEqualTo(5L),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(5)
        );
    }

    @DisplayName("발급 수 증가가 DB에 나간 뒤 쿠폰 저장이 실패하면, 실패가 그대로 나가고 발급 수·쿠폰이 작업 전과 같다. (INV-801)")
    @Test
    void insertFailureAfterIncrement_rollsBackEverything() {
        Long campaignId = campaign(10);
        Long failingUser = 13L;
        AtomicLong issuedCountInside = new AtomicLong(-1);
        doAnswer(invocation -> {
            // 같은 TX 안에서 발급 수 UPDATE를 DB로 보내고, 반영된 값을 읽은 뒤 실패시킨다 — 실제 SQL이 나갔다가 rollback됨을 보인다.
            entityManager.flush();
            issuedCountInside.set(((Number) entityManager
                .createNativeQuery("SELECT issued_count FROM coupon_campaign WHERE id = " + campaignId)
                .getSingleResult()).longValue());
            throw new DataIntegrityViolationException("쿠폰 저장 실패 주입");
        }).when(issuedCouponRepository).save(argThat((IssuedCoupon c) -> c != null && failingUser.equals(c.getUserId())));

        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
            () -> couponIssuer.issue(campaignId, failingUser));

        assertAll(
            () -> assertThat(exception.getMessage()).isEqualTo("쿠폰 저장 실패 주입"),
            () -> assertThat(issuedCountInside.get()).as("TX 안에서 본 발급 수").isEqualTo(1L),
            () -> assertThat(issuedCount(campaignId)).as("rollback 뒤 발급 수").isZero(),
            () -> assertThat(couponRows(campaignId)).isZero(),
            () -> assertThat(couponQuery.findIssued(campaignId, failingUser)).isEmpty()
        );
    }

    @DisplayName("TX 안 조회가 기존 쿠폰을 못 본 채 insert가 unique에 걸리면, 그 TX는 rollback되고 새 TX에서 찾은 같은 couponId를 EXISTING으로 돌려준다. (INV-802, D-8)")
    @Test
    void uniqueViolation_returnsExistingFromNewTransaction() {
        Long campaignId = campaign(10);
        CouponIssueResult first = couponIssuer.issue(campaignId, 7L);
        // check-then-act 틈을 재현한다: 발급 TX 안 조회만 "없음"을 보게 하고, 그 뒤 조회(새 TX)는 실제로 읽는다.
        doReturn(Optional.empty()).doCallRealMethod()
            .when(issuedCouponRepository).findByCampaignIdAndUserId(eq(campaignId), eq(7L));

        CouponIssueResult second = couponIssuer.issue(campaignId, 7L);

        assertAll(
            () -> assertThat(second.outcome()).isEqualTo(Outcome.EXISTING),
            () -> assertThat(second.coupon().couponId()).isEqualTo(first.coupon().couponId()),
            () -> assertThat(issuedCount(campaignId)).as("unique 위반 TX의 증가도 rollback").isEqualTo(1),
            () -> assertThat(couponRows(campaignId)).isEqualTo(1L)
        );
    }

    @DisplayName("캠페인이 바뀐 뒤에도,")
    @Nested
    class AfterCampaignChange {
        @DisplayName("품절이 되면, 이미 받은 사용자는 같은 couponId로 EXISTING이고 새 사용자는 품절 CONFLICT다.")
        @Test
        void soldOut_existingHolderStillGetsCoupon() {
            Long campaignId = campaign(1);
            CouponIssueResult holder = couponIssuer.issue(campaignId, 7L);

            CouponIssueResult again = couponIssuer.issue(campaignId, 7L);
            CoreException newcomer = assertThrows(CoreException.class, () -> couponIssuer.issue(campaignId, 8L));

            assertAll(
                () -> assertThat(again.outcome()).isEqualTo(Outcome.EXISTING),
                () -> assertThat(again.coupon().couponId()).isEqualTo(holder.coupon().couponId()),
                () -> assertThat(newcomer.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(newcomer.getMessage()).isEqualTo(CouponCampaign.SOLD_OUT_MESSAGE),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1)
            );
        }

        @DisplayName("비활성이 되면, 이미 받은 사용자는 같은 couponId로 EXISTING이고 새 사용자는 비활성 CONFLICT다.")
        @Test
        void deactivated_existingHolderStillGetsCoupon() {
            Long campaignId = campaign(10);
            CouponIssueResult holder = couponIssuer.issue(campaignId, 7L);
            couponCampaignSetup.deactivate(campaignId);

            CouponIssueResult again = couponIssuer.issue(campaignId, 7L);
            CoreException newcomer = assertThrows(CoreException.class, () -> couponIssuer.issue(campaignId, 8L));

            assertAll(
                () -> assertThat(again.outcome()).isEqualTo(Outcome.EXISTING),
                () -> assertThat(again.coupon().couponId()).isEqualTo(holder.coupon().couponId()),
                () -> assertThat(newcomer.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(newcomer.getMessage()).isEqualTo(CouponCampaign.INACTIVE_MESSAGE),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(couponCampaignSetup.get(campaignId).active()).isFalse()
            );
        }

        @DisplayName("혜택이 바뀌면, 이미 받은 쿠폰은 발급 순간의 혜택 그대로이고 새로 받는 쿠폰만 바뀐 혜택이다. (INV-806, D-15)")
        @Test
        void benefitChange_doesNotRewriteIssuedCoupon() {
            Long campaignId = campaign(10);
            CouponIssueResult before = couponIssuer.issue(campaignId, 7L);

            couponCampaignSetup.changeBenefit(campaignId, "3,000원 할인권", 3_000L);
            CouponIssueResult after = couponIssuer.issue(campaignId, 8L);

            IssuedCouponInfo stored = couponQuery.findIssued(campaignId, 7L).orElseThrow();
            assertAll(
                () -> assertThat(stored.benefitDescription()).isEqualTo("5,000원 할인권"),
                () -> assertThat(stored.discountAmount()).isEqualTo(5_000L),
                () -> assertThat(couponQuery.listMine(7L)).containsExactly(before.coupon()),
                () -> assertThat(after.coupon().benefitDescription()).isEqualTo("3,000원 할인권"),
                () -> assertThat(after.coupon().discountAmount()).isEqualTo(3_000L)
            );
        }
    }

    @DisplayName("보유 목록은 요청자 것만, 발급 시각 내림차순(같으면 couponId 내림차순)이다. (D-16)")
    @Test
    void listMine_onlyOwnInStableOrder() {
        Long first = campaign(10);
        Long second = campaign(10);
        Long third = campaign(10);
        IssuedCouponInfo c1 = couponIssuer.issue(first, 7L).coupon();
        IssuedCouponInfo c2 = couponIssuer.issue(second, 7L).coupon();
        IssuedCouponInfo c3 = couponIssuer.issue(third, 7L).coupon();
        couponIssuer.issue(first, 8L);

        List<IssuedCouponInfo> mine = couponQuery.listMine(7L);

        assertAll(
            () -> assertThat(mine).extracting(IssuedCouponInfo::couponId)
                .containsExactly(c3.couponId(), c2.couponId(), c1.couponId()),
            () -> assertThat(mine).extracting(IssuedCouponInfo::userId).containsOnly(7L),
            () -> assertThat(mine).isSortedAccordingTo((a, b) -> {
                int byTime = b.issuedAt().compareTo(a.issuedAt());
                return byTime != 0 ? byTime : Long.compare(b.couponId(), a.couponId());
            })
        );
    }

    private int issuedCount(Long campaignId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
        return value == null ? -1 : value;
    }

    private long couponRows(Long campaignId) {
        Long value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM issued_coupon WHERE campaign_id = ?", Long.class, campaignId);
        return value == null ? -1L : value;
    }

    /** 실행 하나의 결과: 결과 또는 예외 중 하나. */
    private record Attempt(CouponIssueResult result, Throwable error) {
    }

    private static List<String> errorsOf(List<Attempt> outcomes) {
        return outcomes.stream()
            .filter(o -> o.error() != null)
            .map(o -> o.error().getClass().getSimpleName() + ": " + o.error().getMessage())
            .toList();
    }

    /** 모두 준비된 뒤 한 번에 출발시키고, 끝까지 기다려 결과·예외를 모은다. 순서는 DB 락에 맡긴다. */
    private static List<Attempt> runConcurrently(List<Callable<CouponIssueResult>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CouponIssueResult>> futures = new ArrayList<>();
            for (Callable<CouponIssueResult> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("출발 신호를 받지 못했습니다.");
                    }
                    return request.call();
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("모든 실행 준비").isTrue();
            start.countDown();

            List<Attempt> outcomes = new ArrayList<>();
            for (Future<CouponIssueResult> future : futures) {
                try {
                    outcomes.add(new Attempt(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Attempt(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("실행 종료").isTrue();
        }
    }
}
