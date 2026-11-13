package com.loopers.infrastructure.coupon.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.coupon.queue.CouponQueueInconsistentStateException;
import com.loopers.application.coupon.queue.CouponQueueWorkerState;
import com.loopers.application.coupon.queue.CouponRequestQueue.Claim;
import com.loopers.application.coupon.queue.CouponRequestQueue.SubmitOutcome;
import com.loopers.application.coupon.queue.CouponRequestQueue.SubmitResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Testcontainers
class RedisCouponQueueTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.0").withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static RedisTemplate<String, String> redis;
    private RedisCouponQueue queue;
    private CouponQueueWorkerState state;
    private String prefix;

    @BeforeAll
    static void connect() {
        var config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        redis = new RedisTemplate<>();
        redis.setConnectionFactory(factory);
        var serializer = new StringRedisSerializer();
        redis.setKeySerializer(serializer);
        redis.setValueSerializer(serializer);
        redis.setHashKeySerializer(serializer);
        redis.setHashValueSerializer(serializer);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void prepare() {
        prefix = "coupon:{test-" + UUID.randomUUID() + "}";
        state = new CouponQueueWorkerState(true);
        queue = new RedisCouponQueue(redis, 100, prefix, state);
    }

    @Test
    void 작업자가_중단된_서버에서는_기존_신청을_유지하고_새_신청만_거절한다() {
        var existing = queue.submit(1, 42);
        state.pause();

        var duplicate = queue.submit(1, 42);
        assertThat(duplicate.outcome()).isEqualTo(SubmitOutcome.EXISTING);
        assertThat(duplicate.requestId()).isEqualTo(existing.requestId());
        assertThat(queue.submit(1, 43).outcome()).isEqualTo(SubmitOutcome.PAUSED);
        assertThat(redis.opsForSet().size(prefix + ":waiting")).isEqualTo(1);
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", existing.requestId())).isTrue();
    }

    @Test
    void 한_서버의_작업자_중단은_다른_정상_서버의_접수까지_닫지_않는다() {
        var healthyServer = new RedisCouponQueue(redis, 100, prefix, new CouponQueueWorkerState(true));
        state.pause();

        assertThat(queue.submit(1, 42).outcome()).isEqualTo(SubmitOutcome.PAUSED);
        assertThat(healthyServer.submit(1, 42).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
    }

    @Test
    void 빈_대기열은_100건까지_받고_101번째는_거절하며_작업자가_꺼내면_새_요청을_받는다() {
        for (int userId = 1; userId <= 100; userId++) {
            assertThat(queue.submit(1, userId).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
        }
        assertThat(queue.submit(1, 101).outcome()).isEqualTo(SubmitOutcome.FULL);
        assertThat(queue.submit(1, 1).outcome()).isEqualTo(SubmitOutcome.EXISTING);
        assertThat(redis.opsForSet().size(prefix + ":waiting")).isEqualTo(100);

        var claim = queue.claim("server-a").orElseThrow();
        assertThat(claim.userId()).isBetween(1L, 100L);
        assertThat(queue.submit(1, 101).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
        assertThat(queue.submit(1, 102).outcome()).isEqualTo(SubmitOutcome.FULL);
    }

    @Test
    void 같은_사용자의_재신청은_기존_요청을_유지하고_다른_캠페인은_별도로_접수한다() {
        var first = queue.submit(1, 42);
        var duplicate = queue.submit(1, 42);
        var anotherCampaign = queue.submit(2, 42);

        assertThat(first.outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
        assertThat(duplicate.outcome()).isEqualTo(SubmitOutcome.EXISTING);
        assertThat(duplicate.requestId()).isEqualTo(first.requestId());
        assertThat(anotherCampaign.outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
        assertThat(anotherCampaign.requestId()).isNotEqualTo(first.requestId());
        assertThat(redis.opsForSet().isMember(prefix + ":waiting", first.requestId())).isTrue();
    }

    @Test
    void 확정된_품절은_해당_캠페인의_신규_신청만_막고_기존_신청과_다른_캠페인은_유지한다() {
        var existing = queue.submit(1, 42);
        queue.markSoldOut(1);
        queue.markSoldOut(1);

        var sameUser = queue.submit(1, 42);
        assertThat(sameUser.outcome()).isEqualTo(SubmitOutcome.EXISTING);
        assertThat(sameUser.requestId()).isEqualTo(existing.requestId());
        assertThat(queue.submit(1, 43).outcome()).isEqualTo(SubmitOutcome.SOLD_OUT);
        assertThat(queue.claim("server-a").orElseThrow().requestId()).isEqualTo(existing.requestId());
        assertThat(queue.submit(2, 43).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
    }

    @Test
    void 동시에_130명이_신청해도_빈_대기열에는_정확히_100건만_들어간다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<SubmitOutcome>>();
            for (int index = 1; index <= 130; index++) {
                long userId = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return queue.submit(1, userId).outcome();
                }));
            }
            start.countDown();
            var outcomes = new ArrayList<SubmitOutcome>();
            for (var future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream().filter(SubmitOutcome.ACCEPTED::equals).count()).isEqualTo(100);
            assertThat(outcomes.stream().filter(SubmitOutcome.FULL::equals).count()).isEqualTo(30);
        }
    }

    @Test
    void 한_사용자가_동시에_100번_신청해도_신청은_한_건이고_대기열은_한_자리만_차지한다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<SubmitResult>>();
            for (int index = 0; index < 100; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return queue.submit(1, 42);
                }));
            }
            start.countDown();
            var results = new ArrayList<SubmitResult>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(result -> result.outcome() == SubmitOutcome.ACCEPTED).count())
                    .isEqualTo(1);
            assertThat(results.stream().filter(result -> result.outcome() == SubmitOutcome.EXISTING).count())
                    .isEqualTo(99);
            assertThat(results.stream().map(SubmitResult::requestId).distinct().count()).isEqualTo(1);
            assertThat(redis.opsForSet().size(prefix + ":waiting")).isEqualTo(1);
        }
    }

    @Test
    void 여러_작업자가_동시에_꺼내도_각_신청은_한_작업자만_가져간다() throws Exception {
        for (int userId = 1; userId <= 100; userId++) {
            assertThat(queue.submit(1, userId).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
        }
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<Claim>>();
            for (int index = 0; index < 100; index++) {
                String worker = "worker-" + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return queue.claim(worker).orElseThrow();
                }));
            }
            start.countDown();
            var claims = new ArrayList<Claim>();
            for (var future : futures) {
                claims.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(claims.stream().map(Claim::requestId).distinct().count()).isEqualTo(100);
            assertThat(redis.opsForSet().size(prefix + ":waiting")).isZero();
            assertThat(redis.opsForHash().size(prefix + ":processing")).isEqualTo(100);
        }
    }

    @Test
    void 대기열이_다시_꽉_차면_처리중_요청의_재등록을_보류하고_기록을_유지한다() {
        for (int userId = 1; userId <= 100; userId++) {
            queue.submit(1, userId);
        }
        Claim first = queue.claim("server-a").orElseThrow();
        assertThat(queue.submit(1, 101).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);

        assertThat(queue.retry(first)).isFalse();
        assertThat(redis.opsForHash().get(prefix + ":processing", first.requestId())).isEqualTo(first.workerId());
        queue.claim("server-b").orElseThrow();
        assertThat(queue.retry(first)).isTrue();
        assertThat(queue.retry(first)).isTrue();
        assertThat(queue.submit(1, 102).outcome()).isEqualTo(SubmitOutcome.FULL);
    }

    @Test
    void 완료는_소유자만_가능하며_신청_기록을_제거하고_재시도해도_안전하다() {
        var submitted = queue.submit(1, 42);
        Claim claim = queue.claim("server-a").orElseThrow();
        var stranger = new Claim(claim.requestId(), claim.campaignId(), claim.userId(), "another-worker");
        assertThatThrownBy(() -> queue.complete(stranger))
                .isInstanceOf(CouponQueueInconsistentStateException.class);
        assertThat(redis.opsForHash().get(prefix + ":processing", claim.requestId())).isEqualTo(claim.workerId());

        queue.complete(claim);
        queue.complete(claim); // 완료 응답을 받지 못했을 때 동일 요청은 안전하게 재전송 가능하다.
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", submitted.requestId())).isFalse();
        assertThat(redis.opsForHash().hasKey(prefix + ":processing", submitted.requestId())).isFalse();
        assertThat(redis.opsForHash().hasKey(prefix + ":users", "1:42")).isFalse();
        assertThat(redis.hasKey(prefix + ":completed")).isFalse();
        var healthy = new RedisCouponQueue(redis, 100, prefix, new CouponQueueWorkerState(true));
        assertThat(healthy.submit(1, 42).outcome()).isEqualTo(SubmitOutcome.ACCEPTED);
    }

    @Test
    void 이전_완료_응답의_재전송은_새_신청의_중복_인덱스를_삭제하지_않는다() {
        var first = queue.submit(1, 42);
        var claim = queue.claim("server-a").orElseThrow();
        queue.complete(claim);
        var next = queue.submit(1, 42);
        assertThat(next.requestId()).isNotEqualTo(first.requestId());

        queue.complete(claim);
        assertThat(redis.opsForHash().get(prefix + ":users", "1:42")).isEqualTo(next.requestId());
        assertThat(queue.submit(1, 42).outcome()).isEqualTo(SubmitOutcome.EXISTING);
        assertThat(redis.opsForSet().size(prefix + ":waiting")).isEqualTo(1);
    }

    @Test
    void 중단된_작업은_자동_회수되지_않고_소유자를_확인한_후에만_수동_복구한다() {
        var submitted = queue.submit(1, 42);
        var original = queue.claim("terminated-server").orElseThrow();
        var restarted = new RedisCouponQueue(redis, 100, prefix, new CouponQueueWorkerState(true));

        assertThat(restarted.claim("new-server")).isEmpty();
        assertThat(redis.opsForHash().get(prefix + ":processing", submitted.requestId()))
                .isEqualTo(original.workerId());
        var wrongOwner = new Claim(original.requestId(), original.campaignId(), original.userId(), "wrong-owner");
        assertThatThrownBy(() -> restarted.retry(wrongOwner)).isInstanceOf(CoreException.class);

        // 운영자가 기존 작업자와 DB 트랜잭션의 종료를 확인했다.
        assertThat(restarted.retry(original)).isTrue();
        Claim recovered = restarted.claim("new-server").orElseThrow();
        assertThat(recovered.requestId()).isEqualTo(submitted.requestId());
        assertThat(recovered.workerId()).isNotEqualTo(original.workerId());
        assertThatThrownBy(() -> restarted.complete(original)).isInstanceOf(CoreException.class);
        restarted.complete(recovered);
    }

    @Test
    void 수동_재등록_후_대기열만_유실되면_복구_명령을_다시_실행해도_성공으로_처리하지_않는다() {
        var submitted = queue.submit(1, 42);
        Claim claim = queue.claim("stopped-server").orElseThrow();

        // 기존 작업 종료를 확인하고 재등록한 뒤, 응답 유실과 대기 Set 유실을 가정한다.
        assertThat(queue.retry(claim)).isTrue();
        String requestBefore = (String) redis.opsForHash().get(prefix + ":requests", claim.requestId());
        redis.opsForSet().remove(prefix + ":waiting", claim.requestId());

        assertThatThrownBy(() -> queue.retry(claim)).isInstanceOf(CoreException.class);
        assertThat(redis.opsForHash().get(prefix + ":requests", claim.requestId())).isEqualTo(requestBefore);
        assertThat(redis.opsForHash().get(prefix + ":users", "1:42")).isEqualTo(submitted.requestId());
        assertThat(redis.opsForSet().isMember(prefix + ":waiting", claim.requestId())).isFalse();
        assertThat(redis.opsForHash().hasKey(prefix + ":processing", claim.requestId())).isFalse();
    }

    @Test
    void 수동_재등록_후_처리_소유자가_동시에_남아있으면_불일치로_복구를_중단한다() {
        queue.submit(1, 42);
        Claim claim = queue.claim("stopped-server").orElseThrow();
        assertThat(queue.retry(claim)).isTrue();
        redis.opsForHash().put(prefix + ":processing", claim.requestId(), claim.workerId());

        assertThatThrownBy(() -> queue.retry(claim)).isInstanceOf(CoreException.class);
        assertThat(redis.opsForSet().isMember(prefix + ":waiting", claim.requestId())).isTrue();
        assertThat(redis.opsForHash().get(prefix + ":processing", claim.requestId())).isEqualTo(claim.workerId());
    }

    @Test
    void 두_서버의_작업자는_공유_대기열에서_서로_다른_신청을_가져와_완료한다() {
        var first = queue.submit(1, 11);
        var second = queue.submit(1, 22);
        var serverA = new RedisCouponQueue(redis, 100, prefix, new CouponQueueWorkerState(true));
        var serverB = new RedisCouponQueue(redis, 100, prefix, new CouponQueueWorkerState(true));

        Claim claimA = serverA.claim("server-a").orElseThrow();
        Claim claimB = serverB.claim("server-b").orElseThrow();
        assertThat(Set.of(claimA.requestId(), claimB.requestId()))
                .containsExactlyInAnyOrder(first.requestId(), second.requestId());
        assertThat(serverA.claim("server-a")).isEmpty();
        assertThat(serverB.claim("server-b")).isEmpty();

        serverA.complete(claimA);
        serverB.complete(claimB);
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", claimA.requestId())).isFalse();
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", claimB.requestId())).isFalse();
        assertThat(redis.opsForHash().size(prefix + ":processing")).isZero();
    }

    @Test
    void 재시도는_DB_시작_전에_예약되고_복구해도_횟수와_다음_시각이_남는다() throws Exception {
        var submitted = queue.submit(1, 42);
        var original = queue.claim("server-a").orElseThrow();

        assertThat(queue.reserveAttempt(original).attemptCount()).isEqualTo(1);
        var first = queue.scheduleRetry(original);
        assertThat(first.exhausted()).isFalse();
        assertThat(first.delayMillis()).isEqualTo(1_000);

        // 운영자가 첫 실행과 DB 롤백을 확인한 뒤 수동 재등록했다.
        setNextRetryAt(submitted.requestId(), 9_000_000_000_000L);
        assertThat(queue.retry(original)).isTrue();
        var recovered = queue.claim("server-b").orElseThrow();
        assertThat(recovered.requestId()).isEqualTo(submitted.requestId());
        assertThat(recovered.retryDelayMillis()).isPositive();
        assertThat(queue.reserveAttempt(recovered).delayMillis()).isPositive();

        for (int expectedAttempt = 2; expectedAttempt <= 4; expectedAttempt++) {
            forceRetryDue(submitted.requestId());
            assertThat(queue.reserveAttempt(recovered).attemptCount()).isEqualTo(expectedAttempt);
            var schedule = queue.scheduleRetry(recovered);
            assertThat(schedule.exhausted()).isEqualTo(expectedAttempt == 4);
            if (expectedAttempt < 4) {
                assertThat(schedule.delayMillis()).isEqualTo(expectedAttempt == 2 ? 2_000 : 4_000);
            }
        }
        assertThat(queue.reserveAttempt(recovered).exhausted()).isTrue();
        queue.complete(recovered);
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", submitted.requestId())).isFalse();
    }

    @Test
    void 마지막_시도_예약_후_결과_불명으로_중단되면_복구해도_새_DB_시도를_예약하지_않는다() throws Exception {
        var submitted = queue.submit(1, 42);
        var claim = queue.claim("server-a").orElseThrow();
        for (int attempt = 1; attempt <= 4; attempt++) {
            forceRetryDue(submitted.requestId());
            assertThat(queue.reserveAttempt(claim).attemptCount()).isEqualTo(attempt);
            if (attempt < 4) {
                queue.scheduleRetry(claim);
            }
        }

        // 네 번째 시도가 시작됐는지 불명이다. 종료 확인 후 수동 등록해도 횟수는 보존된다.
        assertThat(queue.retry(claim)).isTrue();
        var recovered = queue.claim("server-b").orElseThrow();
        assertThatThrownBy(() -> queue.reserveAttempt(recovered)).isInstanceOf(CoreException.class);
        assertThat(state.acceptingNewRequests()).isFalse();
        assertThat(redis.opsForHash().get(prefix + ":processing", submitted.requestId()))
                .isEqualTo(recovered.workerId());
    }

    @Test
    void 미완료_시도_수동복구는_횟수별_지연을_설정하고_복구_명령을_반복해도_지연을_늘리지_않는다() throws Exception {
        var submitted = queue.submit(1, 42);
        var claim = queue.claim("server-0").orElseThrow();
        var redisTime = new DefaultRedisScript<>(
                "local t = redis.call('TIME'); return t[1] * 1000 + math.floor(t[2] / 1000)", Long.class);

        // 각 실행은 DB 시도 예약 후 결과 기록 전 중단됐고 운영자가 결과를 확인했다.
        for (int attempt = 1; attempt <= 3; attempt++) {
            forceRetryDue(submitted.requestId());
            assertThat(queue.reserveAttempt(claim).attemptCount()).isEqualTo(attempt);
            long before = redis.execute(redisTime, List.of());
            assertThat(queue.retry(claim)).isTrue();
            long next = retryAt(submitted.requestId());
            assertThat(next).isGreaterThanOrEqualTo(before + (1_000L << (attempt - 1)));

            // 첫 응답 유실 뒤 같은 명령을 반복해도 기존 일정이 바뀌지 않는다.
            assertThat(queue.retry(claim)).isTrue();
            assertThat(retryAt(submitted.requestId())).isEqualTo(next);
            claim = queue.claim("server-" + attempt).orElseThrow();
        }
    }

    @Test
    void 종료된_신청을_동시에_다시_보내도_새_신청은_하나만_대기한다() throws Exception {
        var old = queue.submit(1, 42);
        var claim = queue.claim("server-a").orElseThrow();
        queue.complete(claim);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<Future<SubmitResult>>();
            for (int index = 0; index < 20; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return queue.submit(1, 42);
                }));
            }
            start.countDown();
            var results = new ArrayList<SubmitResult>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(result -> result.outcome() == SubmitOutcome.ACCEPTED).count())
                    .isEqualTo(1);
            assertThat(results.stream().map(SubmitResult::requestId).distinct().count()).isEqualTo(1);
            assertThat(results.getFirst().requestId()).isNotEqualTo(old.requestId());
        }
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", old.requestId())).isFalse();
        assertThat(redis.opsForSet().size(prefix + ":waiting")).isEqualTo(1);
    }

    @Test
    void 대기_목록만_유실된_재신청은_접수로_오인하지_않고_수용을_닫는다() {
        var submitted = queue.submit(1, 42);
        redis.delete(prefix + ":waiting");

        assertThatThrownBy(() -> queue.submit(1, 42)).isInstanceOf(CoreException.class);
        assertThat(state.acceptingNewRequests()).isFalse();
        assertThat(redis.opsForHash().get(prefix + ":users", "1:42")).isEqualTo(submitted.requestId());
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", submitted.requestId())).isTrue();
    }

    @Test
    void 처리중인_신청의_소유권_기록이_유실되면_재신청을_접수로_오인하지_않고_중단한다() {
        var submitted = queue.submit(1, 42);
        queue.claim("server-a").orElseThrow();
        redis.opsForHash().delete(prefix + ":processing", submitted.requestId());

        assertThatThrownBy(() -> queue.submit(1, 42)).isInstanceOfSatisfying(CoreException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
        assertThat(state.acceptingNewRequests()).isFalse();
        assertThat(redis.opsForHash().get(prefix + ":users", "1:42")).isEqualTo(submitted.requestId());
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", submitted.requestId())).isTrue();
    }

    @Test
    void 처리중인_신청의_소유자가_일치하지_않으면_재신청을_접수로_오인하지_않고_중단한다() {
        var submitted = queue.submit(1, 42);
        queue.claim("server-a").orElseThrow();
        redis.opsForHash().put(prefix + ":processing", submitted.requestId(), "다른-작업자");

        assertThatThrownBy(() -> queue.submit(1, 42)).isInstanceOfSatisfying(CoreException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
        assertThat(state.acceptingNewRequests()).isFalse();
        assertThat(redis.opsForHash().get(prefix + ":processing", submitted.requestId())).isEqualTo("다른-작업자");
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", submitted.requestId())).isTrue();
    }

    @Test
    void 작업_완료_때_사용자_인덱스가_다른_신청으로_바뀌었으면_보존하고_수용을_닫는다() {
        var submitted = queue.submit(1, 42);
        var claim = queue.claim("server-a").orElseThrow();
        redis.opsForHash().put(prefix + ":users", "1:42", "다른-신청");

        assertThatThrownBy(() -> queue.complete(claim))
                .isInstanceOf(CouponQueueInconsistentStateException.class);
        assertThat(state.acceptingNewRequests()).isFalse();
        assertThat(redis.opsForHash().get(prefix + ":users", "1:42")).isEqualTo("다른-신청");
        assertThat(redis.opsForHash().hasKey(prefix + ":requests", submitted.requestId())).isTrue();
        assertThat(redis.opsForHash().get(prefix + ":processing", submitted.requestId())).isEqualTo(claim.workerId());
    }

    private void forceRetryDue(String requestId) throws Exception {
        setNextRetryAt(requestId, 0);
    }

    private void setNextRetryAt(String requestId, long timestamp) throws Exception {
        String raw = (String) redis.opsForHash().get(prefix + ":requests", requestId);
        ObjectNode value = (ObjectNode) MAPPER.readTree(raw);
        value.put("nextRetryAtMs", timestamp);
        redis.opsForHash().put(prefix + ":requests", requestId, value.toString());
    }

    private long retryAt(String requestId) throws Exception {
        String raw = (String) redis.opsForHash().get(prefix + ":requests", requestId);
        return MAPPER.readTree(raw).path("nextRetryAtMs").asLong();
    }
}
