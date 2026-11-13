package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueResult.Outcome;
import com.loopers.infrastructure.coupon.AdmissionRedisConfig;
import com.loopers.infrastructure.coupon.RedisAdmissionPermits;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 동시 처리 자리를 실제 Redis(공용 테스트 컨테이너)와 MySQL로 본다(docs/week8/decisions.md INV-803~805, D-3~D-7, D-12).
 *
 * <p>D-12 이음매: {@link CouponIssuer}를 spy로 감싸, 붙잡을 사용자로 정한 실행만 {@code issue()} 입구(자리 획득 직후, DB 발급 TX 전)에서
 * latch로 기다리게 한다. 대기에는 deadline을 둔다 — 넘기면 예외로 빠져나와 자리를 반환한다. 실패해도 @AfterEach가 모든 latch를 풀고
 * 스레드를 정리한다.
 *
 * <p>수용은 켜질 때 닫혀 있으므로(D-7) 준비 단계에서 연다. Redis는 전역 리스너(RedisCacheCleanUpListener)가 테스트마다 비운다.
 */
@SpringBootTest
class CouponAdmissionTest {

    private static final Logger log = LoggerFactory.getLogger(CouponAdmissionTest.class);
    private static final long DEADLINE_SECONDS = 10;

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @Autowired
    private CouponAdmission couponAdmission;

    @Autowired
    private CouponCampaignSetup couponCampaignSetup;

    @Autowired
    private CouponQuery couponQuery;

    @Autowired
    private AdmissionPermits admissionPermits;

    @MockitoSpyBean
    private CouponIssuer couponIssuer;

    @Autowired
    @Qualifier(AdmissionRedisConfig.ADMISSION_REDIS_TEMPLATE)
    private RedisTemplate<String, String> admissionRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final Map<Long, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final List<Long> reachedSeam = new CopyOnWriteArrayList<>();
    private CountDownLatch arrived = new CountDownLatch(0);
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        couponAdmission.open();
        executor = Executors.newFixedThreadPool(4);
        doAnswer(invocation -> {
            Long userId = invocation.getArgument(1);
            reachedSeam.add(userId);
            CountDownLatch gate = gates.get(userId);
            if (gate != null) {
                arrived.countDown();
                if (!gate.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("이음매 대기 deadline 초과 userId=" + userId);
                }
            }
            return invocation.callRealMethod();
        }).when(couponIssuer).issue(any(), any());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        gates.values().forEach(CountDownLatch::countDown);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS)).isTrue();
        couponAdmission.open();
        databaseCleanUp.truncateAllTables();
    }

    private Long campaign(int totalQuota) {
        return couponCampaignSetup.create("오픈 기념", totalQuota, "5,000원 할인권", 5_000L).campaignId();
    }

    private void hold(Long... userIds) {
        arrived = new CountDownLatch(userIds.length);
        for (Long userId : userIds) {
            gates.put(userId, new CountDownLatch(1));
        }
    }

    private Future<CouponIssueResult> submit(Long campaignId, Long userId) {
        return executor.submit(() -> couponIssueFacade.issue(campaignId, userId));
    }

    private Set<String> tokens() {
        Set<String> members = admissionRedisTemplate.opsForSet().members(RedisAdmissionPermits.PERMITS_KEY);
        return members == null ? Set.of() : members;
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

    @DisplayName("D-12: 세 실행이 자리를 쥔 채 멈춰 있을 때,")
    @Nested
    class Barrier {
        @DisplayName("token 3개, 4번째는 429(DB 경로에 안 들어감), 하나가 끝나면 그 token만 빠지고 4번째 재요청은 201, 모두 끝나면 token 0개·쿠폰 4장·발급 수 4다.")
        @Test
        void fourthIsRejectedUntilASeatFrees() throws Exception {
            long start = System.nanoTime();
            Long campaignId = campaign(10);
            hold(1L, 2L, 3L);

            // 1. 사용자 1~3이 요청하고 각 실행이 이음매에서 멈춘다
            Future<CouponIssueResult> first = submit(campaignId, 1L);
            Future<CouponIssueResult> second = submit(campaignId, 2L);
            Future<CouponIssueResult> third = submit(campaignId, 3L);
            assertThat(arrived.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).isTrue();

            // 2. token 3개 — 모두 이 서버의 범위이고, TTL이 없다(D-5)
            Set<String> held = new HashSet<>(tokens());
            assertAll(
                () -> assertThat(held).hasSize(3),
                () -> assertThat(held).allMatch(token -> token.startsWith(couponAdmission.serverId() + ":")),
                () -> assertThat(admissionRedisTemplate.getExpire(RedisAdmissionPermits.PERMITS_KEY)).isEqualTo(-1L)
            );

            // 3. 4번째 사용자는 429. DB 발급 경로에 들어가지 않았고 쿠폰도 없다
            CoreException rejected = assertThrows(CoreException.class, () -> couponIssueFacade.issue(campaignId, 4L));
            assertAll(
                () -> assertThat(rejected.getErrorType()).isEqualTo(ErrorType.TOO_MANY_REQUESTS),
                () -> verify(couponIssuer, never()).issue(campaignId, 4L),
                () -> assertThat(reachedSeam).doesNotContain(4L),
                () -> assertThat(couponQuery.findIssued(campaignId, 4L)).isEmpty(),
                () -> assertThat(tokens()).isEqualTo(held)
            );

            // 4. 하나를 풀면 발급·종료·반환까지 끝나고, 그 실행의 token만 빠진다
            gates.get(1L).countDown();
            CouponIssueResult firstResult = first.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
            Set<String> afterFirst = tokens();
            assertAll(
                () -> assertThat(firstResult.outcome()).isEqualTo(Outcome.CREATED),
                () -> assertThat(afterFirst).hasSize(2),
                () -> assertThat(held).containsAll(afterFirst)
            );

            // 5. 4번째 사용자가 다시 요청하면 201(CREATED)
            CouponIssueResult retry = couponIssueFacade.issue(campaignId, 4L);
            assertThat(retry.outcome()).isEqualTo(Outcome.CREATED);

            // 6. 나머지를 풀면 token 0개, 쿠폰 4장, 발급 수 4
            gates.get(2L).countDown();
            gates.get(3L).countDown();
            assertAll(
                () -> assertThat(second.get(DEADLINE_SECONDS, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.CREATED),
                () -> assertThat(third.get(DEADLINE_SECONDS, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.CREATED)
            );
            assertAll(
                () -> assertThat(tokens()).isEmpty(),
                () -> assertThat(couponRows(campaignId)).isEqualTo(4L),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(4)
            );
            log.info("[D-12 붙잡기] {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    @DisplayName("자리 반환은,")
    @Nested
    class Release {
        @DisplayName("같은 token을 두 번 반환하거나 모르는 token을 반환해도 남의 token은 지우지 않는다.")
        @Test
        void releasesOnlyOwnToken() {
            String mine = couponAdmission.acquire();
            String other = couponAdmission.acquire();

            couponAdmission.release(mine);
            couponAdmission.release(mine);
            couponAdmission.release(couponAdmission.serverId() + ":unknown");

            assertAll(
                () -> assertThat(tokens()).containsExactly(other),
                () -> assertThat(couponAdmission.isOpen()).isTrue()
            );
        }

        @DisplayName("품절(409)·없는 캠페인(404)으로 끝나도 자리를 반환한다.")
        @Test
        void businessFailureStillReleases() {
            Long campaignId = campaign(1);
            couponIssueFacade.issue(campaignId, 1L);

            CoreException soldOut = assertThrows(CoreException.class, () -> couponIssueFacade.issue(campaignId, 2L));
            CoreException missing = assertThrows(CoreException.class, () -> couponIssueFacade.issue(999_999L, 2L));

            assertAll(
                () -> assertThat(soldOut.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(missing.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                () -> assertThat(tokens()).isEmpty(),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1)
            );
        }
    }

    @DisplayName("수용이 닫혀 있으면,")
    @Nested
    class Closed {
        @DisplayName("신규 발급은 503이고 DB 발급 경로에 들어가지 않으며 쿠폰·발급 수·token이 그대로다.")
        @Test
        void newIssueIsUnavailable_withoutDbEffect() {
            Long campaignId = campaign(10);
            couponAdmission.close();

            CoreException unavailable = assertThrows(CoreException.class, () -> couponIssueFacade.issue(campaignId, 1L));

            assertAll(
                () -> assertThat(unavailable.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE),
                () -> verify(couponIssuer, never()).issue(anyLong(), anyLong()),
                () -> assertThat(couponRows(campaignId)).isZero(),
                () -> assertThat(issuedCount(campaignId)).isZero(),
                () -> assertThat(tokens()).isEmpty()
            );
        }

        @DisplayName("이미 받은 사용자는 자리 없이 같은 쿠폰(EXISTING)을 받는다.")
        @Test
        void existingUserStillGetsCoupon() {
            Long campaignId = campaign(10);
            CouponIssueResult first = couponIssueFacade.issue(campaignId, 1L);
            couponAdmission.close();

            CouponIssueResult again = couponIssueFacade.issue(campaignId, 1L);

            assertAll(
                () -> assertThat(again.outcome()).isEqualTo(Outcome.EXISTING),
                () -> assertThat(again.coupon()).isEqualTo(first.coupon()),
                () -> assertThat(issuedCount(campaignId)).isEqualTo(1)
            );
        }

        @DisplayName("새로 만든 수용은 기본으로 닫혀 있어 503이다(D-7).")
        @Test
        void freshAdmissionIsClosed() {
            CouponAdmission fresh = new CouponAdmission(admissionPermits, 3);

            CoreException unavailable = assertThrows(CoreException.class, fresh::acquire);

            assertAll(
                () -> assertThat(fresh.isOpen()).isFalse(),
                () -> assertThat(unavailable.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE),
                () -> assertThat(tokens()).isEmpty()
            );
        }

        @DisplayName("열린 채로는 남은 token을 지우지 않는다(D-5: 닫기 → 확인 → 지우기 → 열기).")
        @Test
        void leftoverReleaseRequiresClosed() {
            String token = couponAdmission.acquire();

            assertThrows(IllegalStateException.class, () -> couponAdmission.releaseLeftover(token));
            couponAdmission.close();
            couponAdmission.releaseLeftover(token);

            assertThat(tokens()).isEmpty();
        }
    }
}
