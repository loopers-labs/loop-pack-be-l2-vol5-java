package com.loopers.application.coupon;

import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo.Status;
import com.loopers.support.fixture.CouponAdmissionState;
import com.loopers.support.fixture.CouponCampaigns;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * 실제 Redis 에서 수용량 C=3 이 지켜지는지 확인한다(과제 5장). 세 실행을 자리 획득 직후·DB 트랜잭션 시작 전에 붙잡아 둔 상태에서
 * 네 번째 당첨자의 동작을 본다. 실행이 바로 끝나면 네 요청이 차례로 통과해 동시성 초과를 확인할 수 없기 때문이다.
 *
 * <p>barrier 는 DB 저장 진입점({@link CouponIssueWriter#write})에 두고 deadline 을 둔다. 테스트가 실패해도 붙잡은 실행을 모두 풀고
 * 끝날 때까지 기다린 뒤 정리한다.
 */
@SpringBootTest
class CouponAdmissionTest {

    private static final Duration BARRIER_DEADLINE = Duration.ofSeconds(10);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(10);
    private static final long WAITING_OBSERVATION_MILLIS = 500;

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @MockitoSpyBean
    private CouponIssueWriter couponIssueWriter;

    @Autowired
    private CouponCampaigns couponCampaigns;

    @Autowired
    private CouponAdmissionState admissionState;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final Map<Long, CountDownLatch> gates = new ConcurrentHashMap<>();
    private final Set<Long> enteredDbPath = ConcurrentHashMap.newKeySet();
    private final List<Future<CouponIssueInfo>> started = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUpBarrier() {
        doAnswer(invocation -> {
            Long userId = invocation.getArgument(1);
            enteredDbPath.add(userId);
            CountDownLatch gate = gates.get(userId);
            if (gate != null && !gate.await(BARRIER_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("barrier deadline 을 넘겼다. userId=" + userId);
            }
            return invocation.callRealMethod();
        }).when(couponIssueWriter).write(anyLong(), anyLong());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        gates.values().forEach(CountDownLatch::countDown);
        for (Future<CouponIssueInfo> future : started) {
            try {
                future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // 정리 단계다. 검증은 각 테스트 본문에서 이미 끝났다.
            }
        }
        executor.shutdownNow();
        executor.awaitTermination(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        couponCampaigns.clear();
        databaseCleanUp.truncateAllTables();
    }

    private Future<CouponIssueInfo> requestHeldAtDbEntry(Long campaignId, long userId) {
        gates.put(userId, new CountDownLatch(1));
        return request(campaignId, userId);
    }

    private Future<CouponIssueInfo> request(Long campaignId, long userId) {
        Future<CouponIssueInfo> future = executor.submit(() -> couponIssueFacade.issue(campaignId, userId));
        started.add(future);
        return future;
    }

    private void awaitThreeHeld(Long campaignId) {
        await().atMost(BARRIER_DEADLINE).until(() ->
            enteredDbPath.containsAll(Set.of(1L, 2L, 3L)) && admissionState.runningTokens(campaignId).size() == 3);
    }

    private CouponIssueInfo resultOf(Future<CouponIssueInfo> future) throws Exception {
        return future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private long couponRows(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupons where campaign_id = ?", Long.class, campaignId);
    }

    private long couponRowsOf(Long campaignId, long userId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from coupons where campaign_id = ? and user_id = ?", Long.class, campaignId, userId);
    }

    private long ticketRowsOf(Long campaignId, long userId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from coupon_issue_tickets where campaign_id = ? and user_id = ?", Long.class, campaignId, userId);
    }

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaigns where id = ?", Integer.class, campaignId);
    }

    @DisplayName("C=3 에서 세 실행이 자리를 쥐고 있으면 네 번째 당첨자는 DB 에 들어가지 않고 기다리다가, 자리가 반환되면 그 자리로 발급된다.")
    @Test
    void fourthWinnerWaitsThenIssues_whenOneOfThreeRunningFinishes() throws Exception {
        // arrange: 한도는 실험을 가리지 않게 넉넉히 둔다
        Long campaignId = couponCampaigns.open(10);
        Future<CouponIssueInfo> first = requestHeldAtDbEntry(campaignId, 1L);
        Future<CouponIssueInfo> second = requestHeldAtDbEntry(campaignId, 2L);
        Future<CouponIssueInfo> third = requestHeldAtDbEntry(campaignId, 3L);
        awaitThreeHeld(campaignId);

        // act 1: 네 번째 요청
        Future<CouponIssueInfo> fourth = request(campaignId, 4L);
        Thread.sleep(WAITING_OBSERVATION_MILLIS);

        // assert 1: 자리는 3개 그대로이고 네 번째는 당첨만 된 채 DB 경로 밖에서 기다린다
        assertThat(fourth.isDone()).isFalse();
        assertThat(admissionState.runningTokens(campaignId)).hasSize(3);
        assertThat(admissionState.winnerOf(campaignId, 4L)).isEqualTo("PENDING");
        assertThat(enteredDbPath).doesNotContain(4L);
        assertThat(couponRowsOf(campaignId, 4L)).isZero();

        // act 2: 한 실행을 진행시킨다
        gates.get(1L).countDown();

        // assert 2: 그 실행이 끝나 자리를 반환하고, 네 번째가 그 자리로 발급된다
        assertThat(resultOf(first).status()).isEqualTo(Status.ISSUED);
        assertThat(resultOf(fourth).status()).isEqualTo(Status.ISSUED);
        assertThat(enteredDbPath).contains(4L);

        // act 3: 나머지 실행도 끝낸다
        gates.get(2L).countDown();
        gates.get(3L).countDown();

        // assert 3: 쿠폰 4장·발급 수 4, 남은 자리 0
        assertThat(resultOf(second).status()).isEqualTo(Status.ISSUED);
        assertThat(resultOf(third).status()).isEqualTo(Status.ISSUED);
        assertThat(couponRows(campaignId)).isEqualTo(4);
        assertThat(issuedCount(campaignId)).isEqualTo(4);
        assertThat(admissionState.runningTokens(campaignId)).isEmpty();
    }

    @DisplayName("대기 시간 안에 자리가 비지 않으면 네 번째 당첨자는 당첨 기록을 남기고 202(PENDING)를 받으며, 자리는 늘지 않는다.")
    @Test
    void returnsAcceptedWithTicket_whenNoSlotFreesWithinWaitTimeout() throws Exception {
        // arrange
        Long campaignId = couponCampaigns.open(10);
        requestHeldAtDbEntry(campaignId, 1L);
        requestHeldAtDbEntry(campaignId, 2L);
        requestHeldAtDbEntry(campaignId, 3L);
        awaitThreeHeld(campaignId);

        // act
        CouponIssueInfo fourth = resultOf(request(campaignId, 4L));

        // assert
        assertThat(fourth.status()).isEqualTo(Status.PENDING);
        assertThat(ticketRowsOf(campaignId, 4L)).isEqualTo(1);
        assertThat(admissionState.winnerOf(campaignId, 4L)).isEqualTo("TICKETED");
        assertThat(admissionState.runningTokens(campaignId)).hasSize(3);
        assertThat(enteredDbPath).doesNotContain(4L);
        assertThat(couponRowsOf(campaignId, 4L)).isZero();
    }
}
