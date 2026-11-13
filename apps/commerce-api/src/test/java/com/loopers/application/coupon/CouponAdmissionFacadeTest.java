package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponAdmissionFacade.ApplyInfo;
import com.loopers.application.coupon.CouponAdmissionFacade.ApplyOutcome;
import com.loopers.application.coupon.CouponAdmissionFacade.ClaimInfo;
import com.loopers.application.coupon.CouponAdmissionFacade.Status;
import com.loopers.application.coupon.CouponAdmissionFacade.StatusInfo;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionRedisConfig;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionGate.CloseReason;
import com.loopers.domain.coupon.CouponAdmissionGate.Closure;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueCircuit;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisCouponAdmissionQueue;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.ConcurrentRunner;
import com.loopers.utils.ConcurrentRunner.Run;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 8주차 설계 §5·§7·§8 의 조립을 Testcontainers Redis·MySQL 로 검증한다. test 프로파일: C=3, admitted/permit TTL 1초, 하트비트 200ms, 서킷 min-calls 5.
 * DB 오류는 CouponIssueService spy 로 주입하고(§11), DB 가 멈춘 장면은 spy 안에서 래치를 기다려 만든다 (D4-3).
 * 서킷은 전체에 하나인 싱글톤이라 테스트마다 ResettableCouponIssueCircuit 으로 초기 상태로 되돌린다.
 */
@SpringBootTest
class CouponAdmissionFacadeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long LATCH_TIMEOUT_SECONDS = 5;
    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));

    @TestConfiguration
    static class ResettableCircuitConfig {
        /** 운영 Clock 빈 대신 오프셋을 더할 수 있는 시계를 끼운다. 어댑터·서비스·서킷이 모두 이 시계를 본다. */
        @Primary
        @Bean
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }

        @Primary
        @Bean
        ResettableCouponIssueCircuit resettableCouponIssueCircuit(CouponAdmissionProperties properties, Clock clock) {
            return new ResettableCouponIssueCircuit(properties, clock);
        }

        @Primary
        @Bean
        FaultInjectingCouponAdmissionQueue faultInjectingCouponAdmissionQueue(RedisCouponAdmissionQueue delegate) {
            return new FaultInjectingCouponAdmissionQueue(delegate);
        }
    }

    @MockitoSpyBean
    private CouponIssueService couponIssueService;

    private final CouponAdmissionFacade facade;
    private final CouponAdmissionGate gate;
    private final FaultInjectingCouponAdmissionQueue queue;
    private final ResettableCouponIssueCircuit circuit;
    private final AdjustableClock clock;
    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponJpaRepository couponJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final RedisTemplate<String, String> redis;
    private final MeterRegistry meterRegistry;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    private Long campaignId;
    private String gen;

    @Autowired
    CouponAdmissionFacadeTest(
        CouponAdmissionFacade facade,
        CouponAdmissionGate gate,
        FaultInjectingCouponAdmissionQueue queue,
        ResettableCouponIssueCircuit circuit,
        AdjustableClock clock,
        CouponCampaignRepository couponCampaignRepository,
        CouponJpaRepository couponJpaRepository,
        UserJpaRepository userJpaRepository,
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redis,
        MeterRegistry meterRegistry,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.facade = facade;
        this.gate = gate;
        this.queue = queue;
        this.circuit = circuit;
        this.clock = clock;
        this.couponCampaignRepository = couponCampaignRepository;
        this.couponJpaRepository = couponJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        circuit.reset();
        campaignId = saveCampaign(100);
        gen = gate.open(campaignId);
    }

    @AfterEach
    void tearDown() {
        // @TestConfiguration 을 둔 뒤로 spy 의 자동 reset 이 동작하지 않아 stub·호출 기록이 다음 테스트로 넘어갔다. 명시적으로 되돌린다
        Mockito.reset(couponIssueService);
        queue.reset();
        clock.reset();
        gate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    // ---- fixture ----

    private Long saveCampaign(int totalQuota) {
        return couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", totalQuota, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT)).getId();
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private List<Long> saveUsers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(saveUser());
        }
        return ids;
    }

    /** 접수 → 승격까지 끝낸 사용자 목록. C=3 이라 한 번의 promote 로 최대 3명이 승격된다. */
    private List<Long> admittedUsers(int count) {
        List<Long> users = saveUsers(count);
        users.forEach(userId -> facade.apply(campaignId, userId));
        queue.promote(campaignId, gen);
        return users;
    }

    /** 승격을 거치지 않고 admitted 에 직접 넣는다. C 를 넘는 승격자를 만들어 세마포어 방어선(429)을 보는 데만 쓴다. */
    private void admitDirectly(Long userId) {
        redis.opsForZSet().add("coupon:" + campaignId + ":admitted", String.valueOf(userId), System.currentTimeMillis());
    }

    private long permitCount() {
        Long size = redis.opsForZSet().size("coupon:" + campaignId + ":permits");
        return size == null ? 0 : size;
    }

    private int issuedCount() {
        return couponCampaignRepository.findById(campaignId).orElseThrow().getIssuedCount();
    }

    private double counter(String name, String tagKey, String tagValue) {
        return meterRegistry.counter(name, tagKey, tagValue).count();
    }

    private static void assertCoreException(Throwable error, ErrorType errorType) {
        assertThat(error).isInstanceOf(CoreException.class);
        assertThat(((CoreException) error).getErrorType()).isEqualTo(errorType);
    }

    private static TransientDataAccessResourceException transientFailure() {
        return new TransientDataAccessResourceException("DB 일시 오류(주입)");
    }

    // ---- tests ----

    @DisplayName("접수 (§5-1, §8-2, D7)")
    @Nested
    class Apply {
        @DisplayName("처음 접수하면 ACCEPTED 와 접수 번호 1을, 같은 사용자가 다시 접수하면 ALREADY_ACCEPTED 와 같은 번호를 받는다.")
        @Test
        void acceptsThenIdempotent() {
            Long userId = saveUser();

            ApplyInfo first = facade.apply(campaignId, userId);
            ApplyInfo retry = facade.apply(campaignId, userId);

            assertThat(first).isEqualTo(new ApplyInfo(ApplyOutcome.ACCEPTED, 1L, null));
            assertThat(retry).isEqualTo(new ApplyInfo(ApplyOutcome.ALREADY_ACCEPTED, 1L, null));
        }

        @DisplayName("이미 발급된 사용자의 접수는 ALREADY_ISSUED 와 couponId 를 돌려주고 Redis 를 건드리지 않는다.")
        @Test
        void returnsIssuedCoupon_whenAlreadyIssued() {
            Long userId = admittedUsers(1).get(0);
            ClaimInfo claimed = facade.claim(campaignId, userId);

            ApplyInfo result = facade.apply(campaignId, userId);

            assertThat(result).isEqualTo(new ApplyInfo(ApplyOutcome.ALREADY_ISSUED, 0L, claimed.couponId()));
            assertThat(queue.acceptedCount(campaignId)).isEqualTo(1L);
        }

        @DisplayName("D7 총량 5·배수 1.2 → 접수 상한 6. 7명이 접수하면 6명은 ACCEPTED, 7번째는 409 품절이고 reject{sold_out} 가 1 는다.")
        @Test
        void soldOutBeyondAcceptLimit() {
            Long small = saveCampaign(5);
            gate.open(small);
            List<Long> users = saveUsers(7);
            double before = counter(CouponAdmissionMetrics.REJECT, "reason", "sold_out");

            for (int i = 0; i < 6; i++) {
                assertThat(facade.apply(small, users.get(i)).outcome()).isEqualTo(ApplyOutcome.ACCEPTED);
            }
            assertThatThrownBy(() -> facade.apply(small, users.get(6)))
                .satisfies(e -> assertCoreException(e, ErrorType.CONFLICT));

            assertThat(queue.acceptedCount(small)).isEqualTo(6L);
            assertThat(counter(CouponAdmissionMetrics.REJECT, "reason", "sold_out") - before).isEqualTo(1.0);
            gate.close(small);
        }

        @DisplayName("INV-W8-05 gate 가 닫혀 있으면 접수는 503 이고 reject{gate_closed} 가 는다. 접수 번호는 소비되지 않는다.")
        @Test
        void rejectsWhenGateClosed() {
            Long userId = saveUser();
            gate.close(campaignId);
            double before = counter(CouponAdmissionMetrics.REJECT, "reason", "gate_closed");

            assertThatThrownBy(() -> facade.apply(campaignId, userId))
                .satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));

            assertThat(queue.acceptedCount(campaignId)).isZero();
            assertThat(counter(CouponAdmissionMetrics.REJECT, "reason", "gate_closed") - before).isEqualTo(1.0);
        }

        @DisplayName("없는 캠페인은 404, 종료된 캠페인은 409, 식별되지 않은 사용자는 401 이다.")
        @Test
        void rejectsInvalidRequests() {
            Long userId = saveUser();
            CouponCampaign closed = couponCampaignRepository.findById(saveCampaign(10)).orElseThrow();
            closed.close();
            Long closedId = couponCampaignRepository.save(closed).getId();

            assertThatThrownBy(() -> facade.apply(999_999L, userId)).satisfies(e -> assertCoreException(e, ErrorType.NOT_FOUND));
            assertThatThrownBy(() -> facade.apply(closedId, userId)).satisfies(e -> assertCoreException(e, ErrorType.CONFLICT));
            assertThatThrownBy(() -> facade.apply(campaignId, 999_999L)).satisfies(e -> assertCoreException(e, ErrorType.UNAUTHORIZED));
            assertThatThrownBy(() -> facade.apply(campaignId, null)).satisfies(e -> assertCoreException(e, ErrorType.UNAUTHORIZED));
        }
    }

    @DisplayName("상태 조회 (§8-1)")
    @Nested
    class StatusQuery {
        @DisplayName("접수 전 NONE → 접수 후 WAITING(rank) → 승격 후 ADMITTED → 발급 후 ISSUED(couponId) 순으로 바뀐다.")
        @Test
        void followsLifecycle() {
            Long first = saveUser();
            Long second = saveUser();
            assertThat(facade.status(campaignId, second)).isEqualTo(new StatusInfo(Status.NONE, null, null));

            facade.apply(campaignId, first);
            facade.apply(campaignId, second);
            assertThat(facade.status(campaignId, second)).isEqualTo(new StatusInfo(Status.WAITING, 1L, null));

            queue.promote(campaignId, gen);
            assertThat(facade.status(campaignId, second)).isEqualTo(new StatusInfo(Status.ADMITTED, null, null));

            ClaimInfo claimed = facade.claim(campaignId, second);
            assertThat(facade.status(campaignId, second)).isEqualTo(new StatusInfo(Status.ISSUED, null, claimed.couponId()));
        }

        @DisplayName("접수 상한이 찼고 본인은 어디에도 없으면 SOLD_OUT 이다.")
        @Test
        void soldOutWhenAcceptLimitReached() {
            Long small = saveCampaign(1);
            gate.open(small);
            List<Long> users = saveUsers(3);
            facade.apply(small, users.get(0));
            facade.apply(small, users.get(1));

            assertThat(facade.status(small, users.get(2)).status()).isEqualTo(Status.SOLD_OUT);
            gate.close(small);
        }
    }

    @DisplayName("발급 요청 (§5-3, INV-W8-01·03·04)")
    @Nested
    class Claim {
        @DisplayName("승격되지 않은 사용자의 claim 은 409 이고 DB 발급은 호출되지 않는다. 자리도 차지하지 않는다.")
        @Test
        void rejectsNotAdmitted() {
            Long userId = saveUser();
            facade.apply(campaignId, userId);

            assertThatThrownBy(() -> facade.claim(campaignId, userId))
                .satisfies(e -> assertCoreException(e, ErrorType.CONFLICT));

            verify(couponIssueService, never()).issue(any(), any());
            assertThat(permitCount()).isZero();
            assertThat(issuedCount()).isZero();
        }

        @DisplayName("승격된 사용자의 claim 은 발급(created)하고, 자리와 승격 기록을 지운다. 재요청은 같은 couponId 를 created=false 로 돌려준다.")
        @Test
        void issuesAndCleansUp() {
            Long userId = admittedUsers(1).get(0);

            ClaimInfo first = facade.claim(campaignId, userId);
            ClaimInfo retry = facade.claim(campaignId, userId);

            assertThat(first.created()).isTrue();
            assertThat(retry).isEqualTo(new ClaimInfo(first.couponId(), false));
            assertThat(permitCount()).isZero();
            assertThat(meterRegistry.get(CouponAdmissionMetrics.PERMITS).tag("campaign", String.valueOf(campaignId)).gauge().value())
                .as("permits Gauge 는 수집 시점에 ZCARD 를 읽는다").isZero();
            assertThat(queue.isAdmitted(campaignId, userId)).isFalse();
            assertThat(issuedCount()).isEqualTo(1);
            assertThat(couponJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("INV-W8-01 C=3. 3명이 DB 직전에서 멈춘 동안 4번째 claim 은 429 이고 DB 진입 0. 1명이 끝나면 4번째가 들어간다. 모두 끝나면 permits 0, 쿠폰 4.")
        @Test
        void capsConcurrentIssuesAtCapacity() throws Exception {
            List<Long> users = admittedUsers(3);
            Long fourth = saveUser();
            admitDirectly(fourth);
            CountDownLatch entered = new CountDownLatch(3);
            CountDownLatch proceed = new CountDownLatch(1);
            doAnswer(invocation -> {
                entered.countDown();
                assertThat(proceed.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("barrier deadline").isTrue();
                return invocation.callRealMethod();
            }).when(couponIssueService).issue(any(), any());
            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                List<Future<ClaimInfo>> running = users.stream()
                    .map(userId -> executor.submit(() -> facade.claim(campaignId, userId)))
                    .toList();
                assertThat(entered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("3명이 DB 직전까지 도달").isTrue();

                assertThatThrownBy(() -> facade.claim(campaignId, fourth))
                    .satisfies(e -> assertCoreException(e, ErrorType.TOO_MANY_REQUESTS));
                assertThat(permitCount()).isEqualTo(3);
                verify(couponIssueService, times(3)).issue(any(), any());

                proceed.countDown();
                for (Future<ClaimInfo> future : running) {
                    assertThat(future.get(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).created()).isTrue();
                }
            } finally {
                proceed.countDown();
                executor.shutdownNow();
            }

            assertThat(facade.claim(campaignId, fourth).created()).isTrue();
            assertThat(permitCount()).isZero();
            assertThat(issuedCount()).isEqualTo(4);
            assertThat(couponJpaRepository.count()).isEqualTo(4);
        }

        @DisplayName("INV-W8-03 같은 사용자의 claim 2건이 동시에 도착해도 쿠폰 1건, couponId 동일, issued_count 1 이다.")
        @Test
        void sameUserConcurrentClaims() throws Exception {
            Long userId = admittedUsers(1).get(0);
            List<Callable<ClaimInfo>> tasks = List.of(
                () -> facade.claim(campaignId, userId),
                () -> facade.claim(campaignId, userId)
            );

            List<Run<ClaimInfo>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            assertThat(runs).allSatisfy(run -> assertThat(run.error()).isNull());
            assertThat(runs.get(0).value().couponId()).isEqualTo(runs.get(1).value().couponId());
            assertThat(couponJpaRepository.count()).isEqualTo(1);
            assertThat(issuedCount()).isEqualTo(1);
            assertThat(permitCount()).isZero();
        }

        @DisplayName("INV-W8-05 gate 가 닫히면 승격된 사용자의 claim 도 503 이고 DB 신규 효과가 없다.")
        @Test
        void rejectsWhenGateClosed() {
            Long userId = admittedUsers(1).get(0);
            gate.close(campaignId);

            assertThatThrownBy(() -> facade.claim(campaignId, userId))
                .satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));

            verify(couponIssueService, never()).issue(any(), any());
            assertThat(issuedCount()).isZero();
        }

        @DisplayName("INV-W8-02 총량 2 에 승격 3명이 동시에 claim 하면 발급 2, 품절 1(409), issued_count 2. 품절자의 승격은 확정 결과라 지워진다.")
        @Test
        void soldOutAtDb() throws Exception {
            Long small = saveCampaign(2);
            gate.open(small);
            List<Long> users = saveUsers(3);
            users.forEach(userId -> facade.apply(small, userId));
            queue.promote(small, gate.generation(small).orElseThrow());
            List<Callable<ClaimInfo>> tasks = users.stream()
                .map(userId -> (Callable<ClaimInfo>) () -> facade.claim(small, userId))
                .toList();

            List<Run<ClaimInfo>> runs = ConcurrentRunner.runAll(tasks, TIMEOUT);

            long issued = runs.stream().filter(run -> run.error() == null).count();
            long soldOut = runs.stream().filter(run -> run.error() instanceof CoreException e && e.getErrorType() == ErrorType.CONFLICT).count();
            assertThat(issued).isEqualTo(2);
            assertThat(soldOut).isEqualTo(1);
            assertThat(couponCampaignRepository.findById(small).orElseThrow().getIssuedCount()).isEqualTo(2);
            users.forEach(userId -> assertThat(queue.isAdmitted(small, userId)).isFalse());
            gate.close(small);
        }
    }

    @DisplayName("DB 장애 (§7-4, INV-W8-13 ①②⑤)")
    @Nested
    class DbFailure {
        @DisplayName("INV-W8-13① DB 일시 오류 1회 뒤 재시도가 성공하면 쿠폰이 발급되고 승격은 지워진다. 서킷은 CLOSED 다.")
        @Test
        void retriesTransientFailure() {
            Long userId = admittedUsers(1).get(0);
            doThrow(transientFailure()).doCallRealMethod().when(couponIssueService).issue(any(), any());

            ClaimInfo result = facade.claim(campaignId, userId);

            assertThat(result.created()).isTrue();
            verify(couponIssueService, times(2)).issue(any(), any());
            assertThat(queue.isAdmitted(campaignId, userId)).isFalse();
            assertThat(circuit.state()).isEqualTo(CouponIssueCircuit.State.CLOSED);
        }

        @DisplayName("INV-W8-13② 재시도 2회를 다 써도 실패하면 503 이고, 자리는 반납되지만 승격은 유지된다. DB 가 돌아오면 같은 사용자의 claim 이 성공한다.")
        @Test
        void keepsAdmissionAfterExhaustedRetries() {
            Long userId = admittedUsers(1).get(0);
            doThrow(transientFailure()).when(couponIssueService).issue(any(), any());
            double rejectBefore = counter(CouponAdmissionMetrics.REJECT, "reason", "db_unavailable");
            double releaseBefore = counter(CouponAdmissionMetrics.RELEASE, "result", "released");

            assertThatThrownBy(() -> facade.claim(campaignId, userId))
                .satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));

            verify(couponIssueService, times(1 + CouponAdmissionFacade.DB_RETRIES)).issue(any(), any());
            assertThat(permitCount()).as("자리는 항상 반납").isZero();
            assertThat(queue.isAdmitted(campaignId, userId)).as("승격은 유지").isTrue();
            assertThat(issuedCount()).isZero();
            assertThat(counter(CouponAdmissionMetrics.REJECT, "reason", "db_unavailable") - rejectBefore).isEqualTo(1.0);
            assertThat(counter(CouponAdmissionMetrics.RELEASE, "result", "released") - releaseBefore).isEqualTo(1.0);

            doCallReal();
            assertThat(facade.claim(campaignId, userId).created()).isTrue();
            assertThat(queue.isAdmitted(campaignId, userId)).isFalse();
        }

        @DisplayName("INV-W8-13⑤ 품절(409)과 같은 사용자 재요청은 업무 결과라 서킷 실패로 세지 않는다. min-calls(5) 를 넘겨도 CLOSED 다.")
        @Test
        void businessOutcomesAreNotCircuitFailures() {
            // 총량 1 → 접수 상한 2(D7). 두 명만 접수·승격시키고, 한 명은 발급·재요청, 한 명은 품절을 반복시킨다
            Long small = saveCampaign(1);
            gate.open(small);
            List<Long> users = saveUsers(2);
            users.forEach(userId -> facade.apply(small, userId));
            queue.promote(small, gate.generation(small).orElseThrow());

            assertThat(facade.claim(small, users.get(0)).created()).isTrue();
            for (int i = 0; i < 3; i++) {
                assertThat(facade.claim(small, users.get(0)).created()).isFalse();
                assertThatThrownBy(() -> facade.claim(small, users.get(1))).satisfies(e -> assertCoreException(e, ErrorType.CONFLICT));
            }

            assertThat(circuit.state()).isEqualTo(CouponIssueCircuit.State.CLOSED);
            assertThat(circuit.tryAcquire()).isTrue();
            circuit.release();
            gate.close(small);
        }

        @DisplayName("INV-W8-13⑥ 재시도 소진 503 은 승격 시간을 다시 채운다. 원래 만료 시각(TTL 1초)이 지나도 승격이 유지되고, DB 가 돌아오면 재claim 이 성공한다.")
        @Test
        void extendsAdmissionOnSystemFailure() {
            Long userId = admittedUsers(1).get(0);
            doThrow(transientFailure()).when(couponIssueService).issue(any(), any());

            clock.advance(Duration.ofMillis(800));
            assertThatThrownBy(() -> facade.claim(campaignId, userId)).satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));
            clock.advance(Duration.ofMillis(800));
            // 원래 승격 시각 + 1.6초. 연장이 없었다면 승격 Lua 의 만료 정리(TTL 1초)에 걸린다
            assertThat(facade.promoteOnce()).isZero();

            assertThat(queue.isAdmitted(campaignId, userId)).as("승격 유지").isTrue();
            doCallReal();
            assertThat(facade.claim(campaignId, userId).created()).isTrue();
        }

        @DisplayName("INV-W8-13⑦ 이미 만료·제거된 승격은 claim 실패로 되살아나지 않는다(ZADD XX). claim 은 409, 상태는 NONE 이다.")
        @Test
        void doesNotResurrectExpiredAdmission() {
            Long userId = admittedUsers(1).get(0);
            clock.advance(Duration.ofMillis(1_200));
            assertThat(facade.promoteOnce()).as("만료 정리만 일어나고 대기자는 없다").isZero();
            assertThat(queue.isAdmitted(campaignId, userId)).isFalse();
            doThrow(transientFailure()).when(couponIssueService).issue(any(), any());

            assertThatThrownBy(() -> facade.claim(campaignId, userId)).satisfies(e -> assertCoreException(e, ErrorType.CONFLICT));

            assertThat(queue.extendAdmission(campaignId, userId)).as("없는 승격은 XX 로 갱신되지 않는다").isFalse();
            assertThat(queue.isAdmitted(campaignId, userId)).isFalse();
            assertThat(facade.status(campaignId, userId).status()).isEqualTo(Status.NONE);
            verify(couponIssueService, never()).issue(any(), any());
        }

        private void doCallReal() {
            doAnswer(invocation -> invocation.callRealMethod()).when(couponIssueService).issue(any(), any());
        }
    }

    @DisplayName("DB 장애 — 서킷 열림 (§7-4, INV-W8-13 ③)")
    @Nested
    class CircuitOpen {
        @DisplayName("INV-W8-13③ 승격자 5명(min-calls)의 claim 이 연속 실패하면 서킷이 열리고, 다음 claim 은 세마포어·DB 를 호출하지 않고 503 이다. 승격·대기열은 그대로다.")
        @Test
        void opensCircuitAndStopsCallingDb() {
            List<Long> users = saveUsers(6);
            users.forEach(userId -> facade.apply(campaignId, userId));
            users.forEach(CouponAdmissionFacadeTest.this::admitDirectly);
            doThrow(transientFailure()).when(couponIssueService).issue(any(), any());

            for (int i = 0; i < 4; i++) {
                Long userId = users.get(i);
                assertThatThrownBy(() -> facade.claim(campaignId, userId)).satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));
                assertThat(circuit.state()).as("min-calls 5 전에는 열리지 않는다").isEqualTo(CouponIssueCircuit.State.CLOSED);
            }
            assertThatThrownBy(() -> facade.claim(campaignId, users.get(4))).satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));
            assertThat(circuit.state()).isEqualTo(CouponIssueCircuit.State.OPEN);
            int callsSoFar = 5 * (1 + CouponAdmissionFacade.DB_RETRIES);
            verify(couponIssueService, times(callsSoFar)).issue(any(), any());

            assertThatThrownBy(() -> facade.claim(campaignId, users.get(5)))
                .satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));

            // 서킷이 열린 뒤에는 DB 를 호출하지 않으므로 호출 수가 그대로다
            verify(couponIssueService, times(callsSoFar)).issue(any(), any());
            assertThat(permitCount()).isZero();
            users.forEach(userId -> assertThat(queue.isAdmitted(campaignId, userId)).isTrue());
            assertThat(issuedCount()).isZero();
        }
    }

    @DisplayName("세마포어의 승격 만료 확인 (§5-3, D5). 승격 스케줄러는 돌리지 않는다.")
    @Nested
    class AdmissionExpiryAtClaim {
        @DisplayName("승격 스케줄러가 멈춘 상태에서 admitted TTL(1초)이 지난 사용자의 claim 은 409 이고 자리를 잡지 않는다. 만료된 승격은 지워진다.")
        @Test
        void rejectsExpiredAdmission_withoutPromoteRunning() {
            Long userId = admittedUsers(1).get(0);
            clock.advance(Duration.ofMillis(1_200));
            assertThat(queue.isAdmitted(campaignId, userId)).as("승격 Lua 를 돌리지 않아 기록은 아직 남아 있다").isTrue();

            assertThatThrownBy(() -> facade.claim(campaignId, userId)).satisfies(e -> assertCoreException(e, ErrorType.CONFLICT));

            assertThat(permitCount()).isZero();
            assertThat(queue.isAdmitted(campaignId, userId)).as("세마포어가 자기 승격의 만료를 지운다").isFalse();
            verify(couponIssueService, never()).issue(any(), any());
            assertThat(issuedCount()).isZero();
        }

        @DisplayName("extendAdmission 으로 시간이 다시 채워진 사용자는 원래 만료 시각이 지나도 claim 이 통과한다.")
        @Test
        void acceptsExtendedAdmission_pastOriginalExpiry() {
            Long userId = admittedUsers(1).get(0);
            clock.advance(Duration.ofMillis(800));
            assertThat(queue.extendAdmission(campaignId, userId)).isTrue();
            clock.advance(Duration.ofMillis(800));

            ClaimInfo result = facade.claim(campaignId, userId);

            assertThat(result.created()).isTrue();
            assertThat(permitCount()).isZero();
            assertThat(issuedCount()).isEqualTo(1);
        }
    }

    @DisplayName("장애 후 만료 유예 (§7-4 결정 4, INV-W8-13 ⑧⑨)")
    @Nested
    class GraceAfterCircuitOpen {
        /** 다른 사용자 5명을 직접 승격시켜 DB 실패로 서킷을 연다. 대상 사용자는 건드리지 않는다. */
        private void openCircuitWithOthers() {
            List<Long> others = saveUsers(5);
            others.forEach(CouponAdmissionFacadeTest.this::admitDirectly);
            doThrow(transientFailure()).when(couponIssueService).issue(any(), any());
            for (Long userId : others) {
                assertThatThrownBy(() -> facade.claim(campaignId, userId)).satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));
            }
            assertThat(circuit.state()).isEqualTo(CouponIssueCircuit.State.OPEN);
            Mockito.reset(couponIssueService);
        }

        @DisplayName("INV-W8-13⑧ 승격 뒤 서킷이 열리고 admitted-ttl(1초)보다 길게 지난 다음 서킷이 벗어나면, 다시 시도한 적 없는 승격자의 claim 이 성공한다.")
        @Test
        void keepsUntouchedAdmissionThroughOutage() {
            Long userId = admittedUsers(1).get(0);
            openCircuitWithOthers();
            clock.advance(Duration.ofMillis(1_500));
            sleep(1_200);
            assertThat(circuit.state()).as("열림 대기 뒤 HALF_OPEN").isEqualTo(CouponIssueCircuit.State.HALF_OPEN);
            assertThat(circuit.lastLeftOpenAt()).isPresent();
            assertThat(circuit.admittedCutoffMillis(clock.instant(), Duration.ofSeconds(1))).as("유예 중에는 cutoff 0").isZero();

            ClaimInfo result = facade.claim(campaignId, userId);

            assertThat(result.created()).isTrue();
            assertThat(issuedCount()).isEqualTo(1);
        }

        @DisplayName("INV-W8-13⑨ 서킷이 벗어난 뒤 admitted-ttl 이 지나면 평소대로 만료된다. 떠난 승격자는 정리되고 다음 대기자가 승격된다.")
        @Test
        void expiresAgainAfterGrace() {
            List<Long> users = saveUsers(4);
            users.forEach(userId -> facade.apply(campaignId, userId));
            assertThat(facade.promoteOnce()).isEqualTo(3);
            Long waiter = users.get(3);
            openCircuitWithOthers();
            clock.advance(Duration.ofMillis(1_500));
            sleep(1_200);
            assertThat(circuit.state()).isEqualTo(CouponIssueCircuit.State.HALF_OPEN);

            assertThat(facade.promoteOnce()).as("유예 중에는 아무도 만료되지 않아 빈자리가 없다").isZero();
            assertThat(facade.status(campaignId, waiter).status()).isEqualTo(Status.WAITING);

            clock.advance(Duration.ofMillis(1_200));
            int promoted = facade.promoteOnce();

            assertThat(promoted).as("유예가 끝나 떠난 승격자 3명이 정리되고 대기자가 승격된다").isGreaterThan(0);
            assertThat(facade.status(campaignId, waiter).status()).isEqualTo(Status.ADMITTED);
            assertThat(facade.status(campaignId, users.get(0)).status()).isEqualTo(Status.NONE);
        }
    }

    @DisplayName("승격 (§5-2, §7-1, §7-4, INV-W8-06·13, D5)")
    @Nested
    class Promote {
        @DisplayName("INV-W8-06 10명이 순서대로 접수하면 C=3 씩 접수 번호 순으로 승격·발급된다. 발급 순서가 접수 순서와 같다.")
        @Test
        void promotesAndIssuesInSeqOrder() {
            List<Long> users = saveUsers(10);
            users.forEach(userId -> facade.apply(campaignId, userId));
            List<Long> issuedOrder = new ArrayList<>();

            while (issuedOrder.size() < users.size()) {
                int promoted = facade.promoteOnce();
                assertThat(promoted).isGreaterThan(0);
                for (Long userId : users) {
                    if (!issuedOrder.contains(userId) && queue.isAdmitted(campaignId, userId)) {
                        facade.claim(campaignId, userId);
                        issuedOrder.add(userId);
                    }
                }
            }

            assertThat(issuedOrder).containsExactlyElementsOf(users);
            assertThat(facade.promoteOnce()).as("대기열이 비면 0").isZero();
            assertThat(issuedCount()).isEqualTo(10);
        }

        @DisplayName("D5 승격 후 claim 하지 않으면 admitted TTL(테스트 1초) 뒤 다음 대기자가 승격되고, 미응답자는 NONE 이 된다.")
        @Test
        void expiresUnresponsiveAdmission() {
            List<Long> users = saveUsers(4);
            users.forEach(userId -> facade.apply(campaignId, userId));
            assertThat(facade.promoteOnce()).isEqualTo(3);
            assertThat(facade.status(campaignId, users.get(3)).status()).isEqualTo(Status.WAITING);

            sleep(1_200);
            int promoted = facade.promoteOnce();

            assertThat(promoted).as("만료된 3명 자리에 4번째가 들어간다").isEqualTo(1);
            assertThat(facade.status(campaignId, users.get(3)).status()).isEqualTo(Status.ADMITTED);
            assertThat(facade.status(campaignId, users.get(0)).status()).isEqualTo(Status.NONE);
        }

        @DisplayName("§7-1 승격 Lua 가 -1(세대 불일치)을 돌려주면 gate 를 STALE_GENERATION 으로 닫고 아무도 승격하지 않는다.")
        @Test
        void closesGateOnStaleGeneration() {
            Long userId = saveUser();
            facade.apply(campaignId, userId);
            redisCleanUp.truncateAll();

            int promoted = facade.promoteOnce();

            assertThat(promoted).isZero();
            assertThat(gate.isOpen(campaignId)).isFalse();
            assertThat(gate.closure(campaignId)).map(Closure::reason).contains(CloseReason.STALE_GENERATION);
        }

        @DisplayName("§7-1 승격 경로에서 Redis 예외가 나면 gate 를 REDIS_ERROR 로 닫고 reject{redis_error} 가 는다. 다음 promote 는 닫힌 캠페인을 건너뛴다.")
        @Test
        void closesGateOnRedisError() {
            Long userId = saveUser();
            facade.apply(campaignId, userId);
            double before = counter(CouponAdmissionMetrics.REJECT, "reason", "redis_error");
            queue.setMode(FaultInjectingCouponAdmissionQueue.Mode.FAIL_PROMOTE);

            int promoted = facade.promoteOnce();

            assertThat(promoted).isZero();
            assertThat(gate.isOpen(campaignId)).isFalse();
            assertThat(gate.closure(campaignId)).map(Closure::reason).contains(CloseReason.REDIS_ERROR);
            assertThat(counter(CouponAdmissionMetrics.REJECT, "reason", "redis_error") - before).isEqualTo(1.0);
            queue.reset();
            assertThat(facade.promoteOnce()).as("닫힌 gate 의 캠페인은 승격하지 않는다").isZero();
            assertThat(queue.waitingRank(campaignId, userId)).as("대기열은 그대로").contains(0L);
        }

        @DisplayName("INV-W8-13③④ 서킷이 열리면 promoteOnce 는 아무도 승격하지 않는다. 열림 대기(테스트 1초) 뒤 반열림에서 3회 성공하면 닫히고 승격이 재개된다.")
        @Test
        void skipsWhileCircuitOpen_andResumesAfterHalfOpenSuccess() {
            List<Long> admitted = saveUsers(5);
            admitted.forEach(userId -> facade.apply(campaignId, userId));
            admitted.forEach(CouponAdmissionFacadeTest.this::admitDirectly);
            List<Long> waiting = saveUsers(2);
            waiting.forEach(userId -> facade.apply(campaignId, userId));
            doThrow(transientFailure()).when(couponIssueService).issue(any(), any());
            for (Long userId : admitted) {
                assertThatThrownBy(() -> facade.claim(campaignId, userId)).satisfies(e -> assertCoreException(e, ErrorType.SERVICE_UNAVAILABLE));
            }
            assertThat(circuit.state()).isEqualTo(CouponIssueCircuit.State.OPEN);

            assertThat(facade.promoteOnce()).as("③ 서킷 열림 → 승격 0").isZero();
            waiting.forEach(userId -> assertThat(queue.waitingRank(campaignId, userId)).isPresent());

            Mockito.reset(couponIssueService);
            sleep(1_200);
            assertThat(circuit.state()).as("열림 대기 뒤 호출 없이 HALF_OPEN").isEqualTo(CouponIssueCircuit.State.HALF_OPEN);
            for (int i = 0; i < 3; i++) {
                assertThat(facade.claim(campaignId, admitted.get(i)).created()).isTrue();
            }
            assertThat(circuit.state()).as("④ 반열림 3회 성공 → CLOSED").isEqualTo(CouponIssueCircuit.State.CLOSED);

            int promoted = facade.promoteOnce();

            // 직접 admitted 에 넣은 사용자들은 대기열에도 남아 있고 1.2초 뒤 승격 예약이 만료돼 정확한 수는 의미가 없다. 재개 여부만 본다
            assertThat(promoted).as("승격 재개").isGreaterThan(0);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
