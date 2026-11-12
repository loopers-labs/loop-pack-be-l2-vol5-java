package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponAdmissionRepository.FinishOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Registration;
import com.loopers.domain.coupon.CouponAdmissionRepository.RegistrationOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Start;
import com.loopers.domain.coupon.CouponAdmissionRepository.StartOutcome;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.fixture.CouponRedisTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 대기실 Lua 4개+읽기 Lua 를 fixture Redis 컨테이너에서 실제로 실행한다(설계 §12.8·12.11~12.12, WQ2~WQ6·WQ10 의 저장소 부분).
 * HTTP·DB 발급·서비스의 수용 닫기·스케줄러 자동 회차는 다루지 않는다. 실제 Redis 장애·재시작도 다루지 않는다.
 *
 * <p>대상 확인은 기존 CouponAdmissionRedisIntegrationTest 와 같다(CouponRedisTarget 으로 주소 고정·run_id 대조, 실패 시 Redis 명령 없음).
 * 각 테스트는 임의의 큰 campaignId 를 골라 그 캠페인의 키 5개만 소유한다. 시작 때 키가 있으면 지우지 않고 실패하며,
 * 끝나면 worker 종료를 확인한 경우에만 자기 키를 지운다. 다른 키·FLUSHALL·컨테이너 조작은 하지 않는다.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("대기실 Lua 는 실제 Redis 에서 등록·승격·만료·시작·종료를 원자적으로 바꾸고, 설명할 수 없는 상태에는 쓰지 않는다.")
class CouponWaitingRedisIntegrationTest {

    private static final int C = 3;
    private static final long LONG_DURATION_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long SHORT_DURATION_MILLIS = 400;
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final long MAX_EXACT = 9_007_199_254_740_991L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CouponAdmissionRedisRepository repository;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;

    private final WorkerTermination workers = new WorkerTermination();
    private final Set<Long> ownCampaigns = new HashSet<>();
    private boolean targetVerified;
    private long campaignId;

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
    }

    @BeforeEach
    void requireFixtureContainerAndEmptyKeys() {
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
        campaignId = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L);
        claimEmpty(campaignId);
    }

    @AfterEach
    void deleteOwnKeys() {
        if (!targetVerified) {
            return; // 대상 확인 전이면 어떤 Redis 에도 명령을 보내지 않는다.
        }
        if (!workers.safeToClean()) {
            throw new IllegalStateException("worker 종료를 확인하지 못해 대기실 키 정리를 보류한다. [campaigns = " + ownCampaigns + "]");
        }
        for (Long owned : ownCampaigns) {
            master.delete(CouponAdmissionRedisRepository.waitingKeys(owned));
        }
    }

    /** 이 캠페인 키가 모두 비어 있음을 확인하고 소유로 기록한다. 남은 값은 다른 실행의 것일 수 있어 지우지 않는다. */
    private void claimEmpty(long id) {
        for (String key : CouponAdmissionRedisRepository.waitingKeys(id)) {
            assertThat(master.type(key)).as("시작 전 키가 비어 있어야 한다: " + key).isEqualTo(DataType.NONE);
        }
        ownCampaigns.add(id);
    }

    // ---------- WQ2: 등록 ----------

    @DisplayName("신규 등록은 WAITING·후보 ID·순번 1 이고, 재등록은 같은 ID·순번으로 아무것도 바꾸지 않는다. 조회는 rank+1 이다.")
    @Test
    void registerCreatesThenReuses() throws Exception {
        String candidate = UUID.randomUUID().toString();
        Registration first = repository.register(campaignId, 1L, candidate);
        Registration second = register(2L);

        assertAll(
            () -> assertThat(first.outcome()).isEqualTo(RegistrationOutcome.CREATED),
            () -> assertThat(first.snapshot()).isEqualTo(new CouponWaitingSnapshot(campaignId, candidate, CouponWaitingState.WAITING, 1L, null, null)),
            () -> assertThat(second.snapshot().position()).isEqualTo(2L),
            () -> assertThat(entry(1L).get("sequence").asText()).isEqualTo("1"),
            () -> assertThat(entry(1L).has("expiresAtMillis")).isFalse(),
            () -> assertThat(master.opsForHash().get(key("meta"), "nextSequence")).isEqualTo("3"),
            () -> assertThat(master.opsForZSet().score(key("queue"), "1")).isEqualTo(1.0),
            () -> assertThat(master.getExpire(key("entries"))).as("TTL 없음").isEqualTo(-1L),
            () -> assertThat(master.getExpire(key("queue"))).as("TTL 없음").isEqualTo(-1L)
        );

        Map<String, Object> before = dump();
        Registration again = register(1L);
        assertAll(
            () -> assertThat(again.outcome()).isEqualTo(RegistrationOutcome.EXISTING),
            () -> assertThat(again.snapshot()).isEqualTo(first.snapshot()),
            () -> assertThat(dump()).isEqualTo(before),
            () -> assertThat(repository.find(campaignId, 1L)).contains(first.snapshot()),
            () -> assertThat(repository.find(campaignId, 2L).orElseThrow().position()).isEqualTo(2L),
            () -> assertThat(repository.find(campaignId, 3L)).isEmpty()
        );
    }

    @DisplayName("같은 사용자의 동시 등록 16건은 CREATED 하나·나머지 EXISTING 이고 모두 같은 ID 다. queue 항목은 하나다.")
    @Test
    void concurrentSameUserRegistersOnce() throws Exception {
        List<Registration> results = concurrently(16, i -> register(7L));

        long created = results.stream().filter(r -> r.outcome() == RegistrationOutcome.CREATED).count();
        Set<String> ids = new HashSet<>();
        results.forEach(r -> ids.add(r.snapshot().waitingId()));
        assertAll(
            () -> assertThat(created).isEqualTo(1),
            () -> assertThat(results).allSatisfy(r -> assertThat(r.outcome()).isIn(RegistrationOutcome.CREATED, RegistrationOutcome.EXISTING)),
            () -> assertThat(ids).hasSize(1),
            () -> assertThat(master.opsForZSet().size(key("queue"))).isEqualTo(1L),
            () -> assertThat(master.opsForHash().size(key("entries"))).isEqualTo(1L)
        );
    }

    @DisplayName("서로 다른 사용자 16명의 동시 등록은 각각 한 항목이고 순번은 서버가 정한 1~16 이 하나씩이다.")
    @Test
    void concurrentDifferentUsersEachRegister() throws Exception {
        List<Registration> results = concurrently(16, i -> register(100L + i));

        Set<String> sequences = new TreeSet<>();
        for (int i = 0; i < 16; i++) {
            sequences.add(entry(100L + i).get("sequence").asText());
        }
        assertAll(
            () -> assertThat(results).allSatisfy(r -> assertThat(r.outcome()).isEqualTo(RegistrationOutcome.CREATED)),
            () -> assertThat(results.stream().map(r -> r.snapshot().position()).toList())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L),
            () -> assertThat(sequences).hasSize(16),
            () -> assertThat(master.opsForZSet().size(key("queue"))).isEqualTo(16L)
        );
    }

    // ---------- WQ3: 자리 상한·승격 ----------

    @DisplayName("C=3 에서 5명이면 앞 3명만 READY 이고 넷째·다섯째는 WAITING 1·2 다. 종료로 자리가 나면 다음 회차에 앞순번 하나만 승격한다.")
    @Test
    void capacityBoundsAdmissions() {
        Map<Long, String> ids = registerAll(1L, 2L, 3L, 4L, 5L);

        assertThat(process(C, LONG_DURATION_MILLIS)).isEqualTo(new AdmissionProgress(0, 3));
        assertAll(
            () -> assertThat(members("ready")).containsExactlyInAnyOrder("1", "2", "3"),
            () -> assertThat(repository.find(campaignId, 4L).orElseThrow().position()).isEqualTo(1L),
            () -> assertThat(repository.find(campaignId, 5L).orElseThrow().position()).isEqualTo(2L),
            () -> assertThat(occupancy()).isEqualTo(3)
        );
        assertThat(process(C, LONG_DURATION_MILLIS)).as("빈자리 0").isEqualTo(new AdmissionProgress(0, 0));

        assertThat(repository.start(campaignId, 1L, ids.get(1L)).outcome()).isEqualTo(StartOutcome.STARTED);
        assertThat(process(C, LONG_DURATION_MILLIS)).as("시작은 자리를 그대로 쓴다").isEqualTo(new AdmissionProgress(0, 0));
        assertThat(occupancy()).isEqualTo(3);

        assertThat(repository.finish(campaignId, 1L, ids.get(1L), CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.APPLIED);
        assertThat(occupancy()).isEqualTo(2);
        assertThat(process(C, LONG_DURATION_MILLIS)).isEqualTo(new AdmissionProgress(0, 1));
        assertAll(
            () -> assertThat(repository.find(campaignId, 4L).orElseThrow().status()).isEqualTo(CouponWaitingState.READY),
            () -> assertThat(repository.find(campaignId, 5L).orElseThrow().position()).isEqualTo(1L),
            () -> assertThat(occupancy()).isEqualTo(3)
        );
    }

    // ---------- WQ4: 만료 ----------

    @DisplayName("READY 기한은 조회·재등록으로 늘지 않는다. 회수 전에는 EXPIRED 안내만 하고 쓰지 않으며, 회수 뒤 재등록은 새 ID 로 맨 뒤다.")
    @Test
    void readyExpiresWithoutExtension() throws Exception {
        Map<Long, String> ids = registerAll(1L, 2L, 3L);
        assertThat(process(1, SHORT_DURATION_MILLIS)).isEqualTo(new AdmissionProgress(0, 1));

        CouponWaitingSnapshot ready = repository.find(campaignId, 1L).orElseThrow();
        assertAll(
            () -> assertThat(ready.status()).isEqualTo(CouponWaitingState.READY),
            () -> assertThat(register(1L).snapshot()).as("재등록은 기한을 늘리지 않는다").isEqualTo(ready),
            () -> assertThat(repository.find(campaignId, 1L)).contains(ready)
        );

        awaitRedisTimeReaches(ready.expiresAt());
        Map<String, Object> before = dump();
        Registration expiredNotice = register(1L);
        assertAll(
            () -> assertThat(repository.find(campaignId, 1L).orElseThrow().status()).isEqualTo(CouponWaitingState.EXPIRED),
            () -> assertThat(expiredNotice.outcome()).isEqualTo(RegistrationOutcome.EXISTING),
            () -> assertThat(expiredNotice.snapshot().status()).isEqualTo(CouponWaitingState.EXPIRED),
            () -> assertThat(expiredNotice.snapshot().waitingId()).isEqualTo(ids.get(1L)),
            () -> assertThat(repository.start(campaignId, 1L, ids.get(1L)).outcome()).isEqualTo(StartOutcome.EXPIRED),
            () -> assertThat(dump()).as("회수 전 안내·시작 거절은 쓰지 않는다").isEqualTo(before),
            () -> assertThat(entry(1L).get("status").asText()).isEqualTo("READY")
        );

        assertThat(process(1, SHORT_DURATION_MILLIS)).isEqualTo(new AdmissionProgress(1, 1));
        assertThat(entry(1L).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(members("ready")).containsExactly("2");

        Registration renewed = register(1L);
        assertAll(
            () -> assertThat(renewed.outcome()).isEqualTo(RegistrationOutcome.CREATED),
            () -> assertThat(renewed.snapshot().waitingId()).isNotEqualTo(ids.get(1L)),
            () -> assertThat(renewed.snapshot().position()).as("남은 대기자 3 뒤").isEqualTo(2L),
            () -> assertThat(entry(1L).get("sequence").asText()).isEqualTo("4"),
            () -> assertThat(repository.start(campaignId, 1L, ids.get(1L)).outcome()).as("옛 ID").isEqualTo(StartOutcome.NOT_FOUND)
        );
    }

    @DisplayName("PROCESSING 은 기한이 지나도 회수하지 않고 자리를 계속 차지한다. 조회도 PROCESSING 이다.")
    @Test
    void processingIsNeverReclaimed() throws Exception {
        Map<Long, String> ids = registerAll(1L, 2L);
        process(1, SHORT_DURATION_MILLIS);
        Instant expiresAt = repository.find(campaignId, 1L).orElseThrow().expiresAt();
        assertThat(repository.start(campaignId, 1L, ids.get(1L)).outcome()).isEqualTo(StartOutcome.STARTED);

        awaitRedisTimeReaches(expiresAt);
        assertAll(
            () -> assertThat(process(1, SHORT_DURATION_MILLIS)).isEqualTo(new AdmissionProgress(0, 0)),
            () -> assertThat(members("processing")).containsExactly("1"),
            () -> assertThat(repository.find(campaignId, 1L).orElseThrow().status()).isEqualTo(CouponWaitingState.PROCESSING),
            () -> assertThat(repository.find(campaignId, 2L).orElseThrow().status()).isEqualTo(CouponWaitingState.WAITING)
        );
    }

    @DisplayName("기한 직전·직후의 start 와 회수가 동시에 와도 결과는 하나다. STARTED 면 회수되지 않고, 아니면 PROCESSING 이 아니다(8회).")
    @Test
    void expiryAndStartRaceHasSingleOutcome() throws Exception {
        List<String> outcomes = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            long user = 1_000L + round;
            String id = register(user).snapshot().waitingId();
            assertThat(process(1, 200)).isEqualTo(new AdmissionProgress(0, 1));
            Instant expiresAt = repository.find(campaignId, user).orElseThrow().expiresAt();
            // 신호 시점을 기한 -4ms ~ +3ms 로 옮겨 가며 양쪽 결과가 모두 나올 기회를 준다. 어느 쪽이든 결과는 하나여야 한다.
            awaitRedisTimeReaches(expiresAt.plusMillis(round - 4L));

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService executor = workers.start(2);
            Future<Start> start;
            Future<AdmissionProgress> reclaim;
            try {
                start = executor.submit(() -> {
                    awaitSignal(go);
                    return repository.start(campaignId, user, id);
                });
                reclaim = executor.submit(() -> {
                    awaitSignal(go);
                    return process(1, 200);
                });
            } finally {
                go.countDown();
                workers.close(executor, TERMINATION_DEADLINE);
            }
            StartOutcome started = start.get(0, TimeUnit.MILLISECONDS).outcome();
            AdmissionProgress progress = reclaim.get(0, TimeUnit.MILLISECONDS);
            String stored = entry(user).get("status").asText();
            outcomes.add(started + "/" + progress.expired() + "/" + stored);

            assertThat(started).isIn(StartOutcome.STARTED, StartOutcome.EXPIRED);
            assertThat(occupancy()).isLessThanOrEqualTo(1);
            if (started == StartOutcome.STARTED) {
                assertAll(
                    () -> assertThat(progress.expired()).isZero(),
                    () -> assertThat(stored).isEqualTo("PROCESSING"),
                    () -> assertThat(members("processing")).containsExactly(String.valueOf(user)),
                    () -> assertThat(members("ready")).isEmpty()
                );
                assertThat(repository.finish(campaignId, user, id, CouponWaitingState.FAILED)).isEqualTo(FinishOutcome.APPLIED);
            } else {
                assertThat(members("processing")).isEmpty();
                assertThat(stored).isEqualTo(progress.expired() == 1 ? "EXPIRED" : "READY");
                if (stored.equals("READY")) {
                    awaitRedisTimeReaches(expiresAt);
                    assertThat(process(1, 200)).isEqualTo(new AdmissionProgress(1, 0));
                }
            }
            assertThat(occupancy()).isZero();
        }
        System.out.println("[coupon-waiting] expiry/start race outcomes(start/expired/stored) = " + outcomes);
    }

    // ---------- WQ5: 시작 ----------

    @DisplayName("start 는 ID·사용자·캠페인이 다르면 NOT_FOUND, WAITING 이면 NOT_READY 이고 쓰지 않는다. 진행 중·발급됨·실패 사용은 각각 구분한다.")
    @Test
    void startBranches() throws Exception {
        Map<Long, String> ids = registerAll(1L, 2L);
        process(1, LONG_DURATION_MILLIS);
        long otherCampaign = campaignId + 1;
        claimEmpty(otherCampaign);

        Map<String, Object> before = dump();
        assertAll(
            () -> assertThat(repository.start(campaignId, 1L, UUID.randomUUID().toString()).outcome()).isEqualTo(StartOutcome.NOT_FOUND),
            () -> assertThat(repository.start(campaignId, 1L, ids.get(2L)).outcome()).as("다른 사용자 ID").isEqualTo(StartOutcome.NOT_FOUND),
            () -> assertThat(repository.start(campaignId, 3L, ids.get(1L)).outcome()).as("항목 없는 사용자").isEqualTo(StartOutcome.NOT_FOUND),
            () -> assertThat(repository.start(otherCampaign, 1L, ids.get(1L)).outcome()).as("다른 캠페인").isEqualTo(StartOutcome.NOT_FOUND),
            () -> assertThat(repository.start(campaignId, 2L, ids.get(2L)).outcome()).isEqualTo(StartOutcome.NOT_READY)
        );
        assertThat(dump()).isEqualTo(before);
        for (String key : CouponAdmissionRedisRepository.waitingKeys(otherCampaign)) {
            assertThat(master.type(key)).isEqualTo(DataType.NONE);
        }

        assertThat(repository.start(campaignId, 1L, ids.get(1L)).outcome()).isEqualTo(StartOutcome.STARTED);
        Start processing = repository.start(campaignId, 1L, ids.get(1L));
        assertThat(processing.outcome()).isEqualTo(StartOutcome.PROCESSING);
        assertThat(processing.snapshot().status()).isEqualTo(CouponWaitingState.PROCESSING);
        assertThat(repository.finish(campaignId, 1L, ids.get(1L), CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.APPLIED);
        Start issued = repository.start(campaignId, 1L, ids.get(1L));
        assertAll(
            () -> assertThat(issued.outcome()).isEqualTo(StartOutcome.ISSUED),
            () -> assertThat(issued.snapshot()).isEqualTo(new CouponWaitingSnapshot(campaignId, ids.get(1L), CouponWaitingState.ISSUED, null, null, null)),
            () -> assertThat(register(1L).snapshot().status()).as("발급 뒤 재등록은 새 대기를 만들지 않는다").isEqualTo(CouponWaitingState.ISSUED)
        );

        process(1, LONG_DURATION_MILLIS);
        assertThat(repository.start(campaignId, 2L, ids.get(2L)).outcome()).isEqualTo(StartOutcome.STARTED);
        assertThat(repository.finish(campaignId, 2L, ids.get(2L), CouponWaitingState.FAILED)).isEqualTo(FinishOutcome.APPLIED);
        assertThat(repository.start(campaignId, 2L, ids.get(2L)).outcome()).as("실패로 사용된 권한").isEqualTo(StartOutcome.ALREADY_USED);
    }

    @DisplayName("같은 권한의 동시 start 16건은 STARTED 하나·나머지 PROCESSING 이고 점유는 하나다.")
    @Test
    void concurrentStartStartsOnce() throws Exception {
        String id = register(1L).snapshot().waitingId();
        process(C, LONG_DURATION_MILLIS);

        List<Start> results = concurrently(16, i -> repository.start(campaignId, 1L, id));

        assertAll(
            () -> assertThat(results.stream().filter(r -> r.outcome() == StartOutcome.STARTED).count()).isEqualTo(1),
            () -> assertThat(results.stream().filter(r -> r.outcome() == StartOutcome.PROCESSING).count()).isEqualTo(15),
            () -> assertThat(members("processing")).containsExactly("1"),
            () -> assertThat(members("ready")).isEmpty()
        );
    }

    // ---------- WQ6: 종료 ----------

    @DisplayName("finish 는 자기 PROCESSING 만 기록·반환하고, 다른 ID·반복·새 등록 뒤 옛 호출은 UNCHANGED 로 아무것도 바꾸지 않는다.")
    @Test
    void finishOnlyAppliesToCurrentProcessing() throws Exception {
        Map<Long, String> ids = registerAll(1L, 2L);
        process(C, LONG_DURATION_MILLIS);
        repository.start(campaignId, 1L, ids.get(1L));
        repository.start(campaignId, 2L, ids.get(2L));

        Map<String, Object> before = dump();
        assertThat(repository.finish(campaignId, 1L, UUID.randomUUID().toString(), CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.UNCHANGED);
        assertThat(repository.finish(campaignId, 3L, ids.get(1L), CouponWaitingState.ISSUED)).as("항목 없음").isEqualTo(FinishOutcome.UNCHANGED);
        assertThat(dump()).isEqualTo(before);

        assertThat(repository.finish(campaignId, 1L, ids.get(1L), CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.APPLIED);
        assertAll(
            () -> assertThat(entry(1L).get("status").asText()).isEqualTo("ISSUED"),
            () -> assertThat(members("processing")).as("다른 점유는 그대로").containsExactly("2")
        );
        Map<String, Object> afterFirst = dump();
        assertThat(repository.finish(campaignId, 1L, ids.get(1L), CouponWaitingState.FAILED)).isEqualTo(FinishOutcome.UNCHANGED);
        assertThat(dump()).isEqualTo(afterFirst);

        assertThat(repository.finish(campaignId, 2L, ids.get(2L), CouponWaitingState.FAILED)).isEqualTo(FinishOutcome.APPLIED);
        Registration renewed = register(2L);
        assertThat(renewed.outcome()).isEqualTo(RegistrationOutcome.CREATED);
        Map<String, Object> afterRenew = dump();
        assertAll(
            () -> assertThat(repository.finish(campaignId, 2L, ids.get(2L), CouponWaitingState.ISSUED)).as("옛 ID").isEqualTo(FinishOutcome.UNCHANGED),
            () -> assertThat(dump()).as("새 항목 불변").isEqualTo(afterRenew),
            () -> assertThat(repository.start(campaignId, 2L, ids.get(2L)).outcome()).as("실패 뒤 권한 복원 없음").isEqualTo(StartOutcome.NOT_FOUND)
        );
    }

    @DisplayName("현재 ID·PROCESSING 이 맞는데 processing 점유가 없으면 UNCHANGED 로 숨기지 않고 INVARIANT_ERROR 이며 쓰지 않는다.")
    @Test
    void finishReportsMissingOccupation() throws Exception {
        String id = register(1L).snapshot().waitingId();
        process(C, LONG_DURATION_MILLIS);
        repository.start(campaignId, 1L, id);
        master.opsForSet().remove(key("processing"), "1"); // 점유 유실을 흉내 낸다. 이 테스트 캠페인의 키다.

        Map<String, Object> before = dump();
        assertThat(repository.finish(campaignId, 1L, id, CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.INVARIANT_ERROR);
        assertThat(dump()).isEqualTo(before);
    }

    // ---------- WQ10: 품절·승격 중단 ----------

    @DisplayName("DB 품절이 전달되면 표시를 켜고 승격을 멈춘다. WAITING/READY 는 SOLD_OUT 으로 보이고 PROCESSING 은 그대로이며, 신규 등록은 SOLD_OUT 이다.")
    @Test
    void soldOutStopsAdmissions() throws Exception {
        Map<Long, String> ids = registerAll(1L, 2L, 3L, 4L, 5L);
        process(C, LONG_DURATION_MILLIS);
        repository.start(campaignId, 1L, ids.get(1L));

        assertThat(repository.processAdmissions(campaignId, C, LONG_DURATION_MILLIS, true, false)).isEqualTo(new AdmissionProgress(0, 0));
        Map<String, Object> afterSoldOut = dump();
        assertAll(
            () -> assertThat(master.opsForHash().get(key("meta"), "soldOut")).isEqualTo("1"),
            () -> assertThat(repository.find(campaignId, 1L).orElseThrow().status()).isEqualTo(CouponWaitingState.PROCESSING),
            () -> assertThat(repository.find(campaignId, 2L).orElseThrow().status()).as("READY").isEqualTo(CouponWaitingState.SOLD_OUT),
            () -> assertThat(repository.find(campaignId, 4L).orElseThrow()).as("WAITING")
                .isEqualTo(new CouponWaitingSnapshot(campaignId, ids.get(4L), CouponWaitingState.SOLD_OUT, null, null, null)),
            () -> assertThat(register(6L).outcome()).isEqualTo(RegistrationOutcome.SOLD_OUT),
            () -> assertThat(dump()).as("조회·품절 등록은 쓰지 않는다").isEqualTo(afterSoldOut)
        );

        assertThat(repository.finish(campaignId, 1L, ids.get(1L), CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.APPLIED);
        assertThat(repository.processAdmissions(campaignId, C, LONG_DURATION_MILLIS, false, true))
            .as("품절 표시는 끄지 않는다").isEqualTo(new AdmissionProgress(0, 0));
        assertThat(master.opsForHash().get(key("meta"), "soldOut")).isEqualTo("1");
    }

    @DisplayName("canAdmit=false 면 기한 지난 READY 회수만 하고 승격·품절 표시는 하지 않는다.")
    @Test
    void cannotAdmitOnlyReclaims() throws Exception {
        registerAll(1L, 2L);
        process(1, SHORT_DURATION_MILLIS);
        awaitRedisTimeReaches(repository.find(campaignId, 1L).orElseThrow().expiresAt());

        assertAll(
            () -> assertThat(repository.processAdmissions(campaignId, 1, SHORT_DURATION_MILLIS, false, false)).isEqualTo(new AdmissionProgress(1, 0)),
            () -> assertThat(entry(1L).get("status").asText()).isEqualTo("EXPIRED"),
            () -> assertThat(repository.find(campaignId, 2L).orElseThrow().position()).isEqualTo(1L),
            () -> assertThat(master.opsForHash().hasKey(key("meta"), "soldOut")).isFalse()
        );
    }

    // ---------- 입력·저장 상태 검증 ----------

    @DisplayName("잘못된 Java 인수는 Redis 에 보내기 전에 거절한다.")
    @Test
    void javaArgumentsRejectedBeforeRedis() {
        assertAll(
            () -> assertThatThrownBy(() -> repository.register(campaignId, null, "x")).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.register(campaignId, 1L, " ")).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.find(0L, 1L)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.processAdmissions(campaignId, 0, 1, false, true)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.processAdmissions(campaignId, 1, 0, false, true)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.start(campaignId, -1L, "x")).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.finish(campaignId, 1L, "x", CouponWaitingState.READY)).isInstanceOf(IllegalArgumentException.class)
        );
        for (String key : CouponAdmissionRedisRepository.waitingKeys(campaignId)) {
            assertThat(master.type(key)).isEqualTo(DataType.NONE);
        }
    }

    @DisplayName("입장 기한이 정확한 정수 범위를 넘는 duration 은 Lua 가 쓰기 전에 오류로 거절한다.")
    @Test
    void durationBeyondExactRangeRejectedBeforeWrite() throws Exception {
        registerAll(1L);
        Map<String, Object> before = dump();

        assertThatThrownBy(() -> process(C, Long.MAX_VALUE)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> process(C, MAX_EXACT)).as("now + duration 이 2^53-1 초과").isInstanceOf(RuntimeException.class);
        assertThat(dump()).isEqualTo(before);
    }

    @DisplayName("순번은 2^53-1 까지 정확히 저장하고, 그 뒤 순번은 쓰기 전에 INVARIANT_ERROR 다.")
    @Test
    void sequenceUpToExactLimitThenExhausted() throws Exception {
        master.opsForHash().put(key("meta"), "nextSequence", String.valueOf(MAX_EXACT));
        assertThat(register(1L).outcome()).isEqualTo(RegistrationOutcome.CREATED);
        assertAll(
            () -> assertThat(entry(1L).get("sequence").asText()).isEqualTo(String.valueOf(MAX_EXACT)),
            () -> assertThat(master.opsForZSet().score(key("queue"), "1")).isEqualTo((double) MAX_EXACT),
            () -> assertThat(master.opsForHash().get(key("meta"), "nextSequence")).isEqualTo(String.valueOf(MAX_EXACT + 1))
        );

        Map<String, Object> before = dump();
        assertThat(register(2L).outcome()).isEqualTo(RegistrationOutcome.INVARIANT_ERROR);
        assertThat(dump()).isEqualTo(before);
    }

    @DisplayName("키 타입이 다르면 다섯 작업 모두 쓰지 않고 INVARIANT_ERROR 또는 예외이며 키 값은 그대로다.")
    @Test
    void wrongKeyTypeIsInvariant() throws Exception {
        String marker = "coupon-waiting-wrongtype-" + UUID.randomUUID();
        master.opsForValue().set(key("queue"), marker);

        assertAll(
            () -> assertThat(register(1L).outcome()).isEqualTo(RegistrationOutcome.INVARIANT_ERROR),
            () -> assertThatThrownBy(() -> repository.find(campaignId, 1L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("INVARIANT_ERROR"),
            () -> assertThatThrownBy(() -> process(C, LONG_DURATION_MILLIS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("INVARIANT_ERROR"),
            () -> assertThat(repository.start(campaignId, 1L, "x").outcome()).isEqualTo(StartOutcome.INVARIANT_ERROR),
            () -> assertThat(repository.finish(campaignId, 1L, "x", CouponWaitingState.ISSUED)).isEqualTo(FinishOutcome.INVARIANT_ERROR)
        );
        assertThat(master.opsForValue().get(key("queue"))).isEqualTo(marker);
        for (String other : List.of("meta", "entries", "ready", "processing")) {
            assertThat(master.type(key(other))).isEqualTo(DataType.NONE);
        }
    }

    @DisplayName("해석할 수 없는 항목·색인 불일치·자리 초과는 쓰지 않고 INVARIANT_ERROR 또는 예외다.")
    @Test
    void corruptStateIsInvariant() throws Exception {
        master.opsForHash().put(key("entries"), "1", "not-json");
        master.opsForZSet().add(key("queue"), "1", 1);
        Map<String, Object> corrupt = dump();
        assertAll(
            () -> assertThat(register(1L).outcome()).isEqualTo(RegistrationOutcome.INVARIANT_ERROR),
            () -> assertThatThrownBy(() -> repository.find(campaignId, 1L)).isInstanceOf(IllegalStateException.class),
            () -> assertThat(repository.start(campaignId, 1L, "x").outcome()).isEqualTo(StartOutcome.INVARIANT_ERROR),
            () -> assertThatThrownBy(() -> process(C, LONG_DURATION_MILLIS)).as("승격 대상 기록 검증").isInstanceOf(IllegalStateException.class)
        );
        assertThat(dump()).isEqualTo(corrupt);
        master.delete(List.of(key("entries"), key("queue")));

        master.opsForZSet().add(key("queue"), "2", 1); // 기록 없는 색인
        Map<String, Object> orphan = dump();
        assertAll(
            () -> assertThat(register(2L).outcome()).isEqualTo(RegistrationOutcome.INVARIANT_ERROR),
            () -> assertThatThrownBy(() -> repository.find(campaignId, 2L)).isInstanceOf(IllegalStateException.class)
        );
        assertThat(dump()).isEqualTo(orphan);
        master.delete(key("queue"));

        registerAll(3L, 4L);
        process(2, LONG_DURATION_MILLIS);
        Map<String, Object> full = dump();
        assertThatThrownBy(() -> process(1, LONG_DURATION_MILLIS)).as("READY+PROCESSING > C").isInstanceOf(IllegalStateException.class);
        assertThat(dump()).isEqualTo(full);
    }

    // ---------- 보조 ----------

    private Registration register(long user) {
        return repository.register(campaignId, user, UUID.randomUUID().toString());
    }

    private Map<Long, String> registerAll(Long... users) {
        Map<Long, String> ids = new TreeMap<>();
        for (Long user : users) {
            Registration registration = register(user);
            assertThat(registration.outcome()).isEqualTo(RegistrationOutcome.CREATED);
            ids.put(user, registration.snapshot().waitingId());
        }
        return ids;
    }

    private AdmissionProgress process(int capacity, long durationMillis) {
        return repository.processAdmissions(campaignId, capacity, durationMillis, false, true);
    }

    private String key(String kind) {
        return "waiting:" + kind + ":v1:" + campaignId;
    }

    private JsonNode entry(long user) throws Exception {
        Object raw = master.opsForHash().get(key("entries"), String.valueOf(user));
        assertThat(raw).as("entry " + user).isNotNull();
        return JSON.readTree((String) raw);
    }

    private Set<String> members(String kind) {
        if (kind.equals("processing")) {
            return master.opsForSet().members(key(kind));
        }
        return master.opsForZSet().range(key(kind), 0, -1);
    }

    private long occupancy() {
        return master.opsForZSet().size(key("ready")) + master.opsForSet().size(key("processing"));
    }

    /** 이 캠페인 키 5개의 전체 내용. 쓰기 없음 확인에 쓴다. */
    private Map<String, Object> dump() {
        Map<String, Object> state = new TreeMap<>();
        state.put("meta", new TreeMap<>(master.opsForHash().entries(key("meta"))));
        state.put("entries", new TreeMap<>(master.opsForHash().entries(key("entries"))));
        for (String kind : List.of("queue", "ready")) {
            List<String> scored = new ArrayList<>();
            Set<TypedTuple<String>> tuples = master.type(key(kind)) == DataType.ZSET
                ? master.opsForZSet().rangeWithScores(key(kind), 0, -1) : Set.of();
            tuples.forEach(t -> scored.add(t.getValue() + "=" + String.format("%.0f", t.getScore())));
            state.put(kind, scored);
        }
        state.put("processing", new TreeSet<>(master.opsForSet().members(key("processing"))));
        for (String kind : List.of("meta", "entries", "queue", "ready", "processing")) {
            state.put(kind + ":type", master.type(key(kind)).code());
        }
        return state;
    }

    private long redisNowMillis() {
        Long now = master.execute((RedisCallback<Long>) c -> c.serverCommands().time());
        return now == null ? 0 : now;
    }

    /** Redis 서버 시각이 target 이상이 될 때까지 유한하게 기다린다. 만료 판단은 Lua 의 TIME 기준이다. */
    private void awaitRedisTimeReaches(Instant target) throws InterruptedException {
        long until = System.nanoTime() + RESULT_DEADLINE.toNanos();
        while (redisNowMillis() < target.toEpochMilli()) {
            if (System.nanoTime() > until) {
                throw new IllegalStateException("Redis 시각이 " + target + " 에 이르지 않았다");
            }
            Thread.sleep(1);
        }
    }

    private static void awaitSignal(CountDownLatch go) throws InterruptedException {
        if (!go.await(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("시작 신호 대기 초과");
        }
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    /** count 개 작업을 동시에 시작하고 유한 기한 안에 결과를 모은다. 종료를 확인하지 못하면 정리를 보류하게 된다. */
    private <T> List<T> concurrently(int count, Task<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = workers.start(count);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit((Callable<T>) () -> {
                    ready.countDown();
                    awaitSignal(go);
                    return task.run(index);
                }));
            }
            assertThat(ready.await(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            go.countDown();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(0, TimeUnit.MILLISECONDS));
        }
        return results;
    }
}
