package com.loopers.verification.localredis;

import com.loopers.CommerceApiApplication;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.SqlCapture;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.infrastructure.coupon.CouponCacheRedisRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.coupon.CouponV1Controller;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.interfaces.scheduler.CouponWaitingScheduler;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.FaultLog;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.PauseGuard;
import com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.RecordingAdmissionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest.OPERATING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 캐시 adapter 를 쓰는 로컬 Redis 장애 R4/R5(장애 상세설계 §3). 기존 Q9/Q10 의 PauseGuard·FaultLog·대기 기록 decorator·endCase·대상 확인을 재사용한다.
 * C1 miss 대역을 넣지 않고 실제 CouponCacheRedisRepository 를 기록 decorator 로 감싼다. 사례마다 컨텍스트를 새로 만들어 로컬 품절 한 칸이 섞이지 않게 한다.
 * 기본 실행에서는 비활성이며 Q9/Q10 과 같은 {@code COUPON_LOCAL_REDIS_FAULT_TEST=true}·{@code COUPON_LOCAL_REDIS_FAULT_LOG} 로만 실행한다. 두 클래스를 동시에 돌리지 않는다.
 *
 * <p>대기 상태 Redis 와 캐시가 같은 master 를 쓰므로 pause 는 둘 다 멈춘다. 캐시 실패와 대기 상태 실패는 decorator 기록으로 구분하며,
 * 통제된 cache-only 결과를 전체 Redis 장애 가용성으로 확대하지 않는다. 자동 입장 회차는 이 컨텍스트에서 대체한다.
 */
@EnabledIfEnvironmentVariable(named = "COUPON_LOCAL_REDIS_FAULT_TEST", matches = "true")
@SpringBootTest(classes = CouponCacheLocalRedisFaultIntegrationTest.Bootstrap.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = SqlCapture.PROPERTY)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("실제 캐시 장애: Redis 가 멈추면 기발급 get·put 실패는 DB 결과로 보완하고, 로컬 품절은 유효 수명 안에서만 Redis·캠페인 DB 없이 409 다.")
class CouponCacheLocalRedisFaultIntegrationTest {

    /** 사례마다 컨텍스트가 새로 생기므로 보류를 클래스 수명 동안 이어간다. */
    private static final List<String> WITHHELD = Collections.synchronizedList(new ArrayList<>());
    /** 제품 기본 로컬 품절 수명. 통과를 위해 바꾸지 않는다. */
    private static final Duration LOCAL_SOLD_OUT_TTL = Duration.ofSeconds(5);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ConfigurationPropertiesScan("com.loopers")
    @ComponentScan(basePackages = "com.loopers", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {RedisTestContainersConfig.class, CommerceApiApplication.class})
    })
    static class Bootstrap {
        @Bean
        FaultLog cacheFaultLog() {
            return FaultLog.open();
        }

        @Bean
        @Primary
        RecordingAdmissionRepository recordingAdmissionRepository(CouponAdmissionRedisRepository redisRepository, FaultLog cacheFaultLog) {
            return new RecordingAdmissionRepository(redisRepository, cacheFaultLog);
        }

        @Bean
        @Primary
        RecordingCacheRepository recordingCacheRepository(CouponCacheRedisRepository redisRepository, FaultLog cacheFaultLog) {
            return new RecordingCacheRepository(redisRepository, cacheFaultLog);
        }
    }

    @DynamicPropertySource
    static void pinToLocalRedis(DynamicPropertyRegistry registry) {
        CouponLocalRedisFaultIntegrationTest.pinToLocalRedis(registry);
    }

    /**
     * 실제 캐시 포트에 위임하는 test decorator. get/put 마다 진입·결과·실패를 기록하고 호출·실패 수를 센다.
     * 지정한 사용자·캠페인의 첫 기발급 put 직전에만 callback 을 한 번 실행한다. callback 자체의 실패는 put 실패와 따로 기록하고 위임하지 않는다.
     */
    static final class RecordingCacheRepository implements CouponCacheRepository {
        private final CouponCacheRepository delegate;
        private final FaultLog log;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        private final AtomicReference<IssuedPutHook> beforeIssuedPut = new AtomicReference<>();
        private volatile String testCase = "불명";
        private volatile long lastSoldOutHitNanos = -1;
        private volatile long lastSoldOutRemainingMillis = -1;

        RecordingCacheRepository(CouponCacheRepository delegate, FaultLog log) {
            this.delegate = delegate;
            this.log = log;
        }

        private record IssuedPutHook(Long userId, Long campaignId, Runnable action) {
        }

        void testCase(String testCase) {
            this.testCase = testCase;
        }

        void beforeFirstIssuedPut(Long userId, Long campaignId, Runnable action) {
            beforeIssuedPut.set(new IssuedPutHook(userId, campaignId, action));
        }

        int calls(String op) {
            return calls.computeIfAbsent(op, k -> new AtomicInteger()).get();
        }

        int failures(String op) {
            return failures.computeIfAbsent(op, k -> new AtomicInteger()).get();
        }

        /** 마지막 실제 Redis 품절 hit 을 돌려받은 단조 시각. 로컬 채움은 이 뒤에 일어난다. */
        long lastSoldOutHitNanos() {
            return lastSoldOutHitNanos;
        }

        long lastSoldOutRemainingMillis() {
            return lastSoldOutRemainingMillis;
        }

        @Override
        public Optional<IssuedCoupon> findIssued(Long userId, Long campaignId) {
            return logged("issued-get", userId, campaignId, () -> delegate.findIssued(userId, campaignId));
        }

        @Override
        public void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis) {
            IssuedPutHook hook = beforeIssuedPut.get();
            if (hook != null && hook.userId().equals(userId) && hook.campaignId().equals(campaignId)
                && beforeIssuedPut.compareAndSet(hook, null)) {
                try {
                    hook.action().run();
                } catch (RuntimeException e) {
                    log.append(event("issued-put-callback-failed " + e.getClass().getSimpleName(), userId, campaignId));
                    throw e;
                }
            }
            logged("issued-put", userId, campaignId, () -> {
                delegate.putIssuedIfAbsent(userId, campaignId, value, ttlMillis);
                return "done";
            });
        }

        @Override
        public OptionalLong findSoldOutTtl(Long campaignId) {
            OptionalLong result = logged("sold-out-get", null, campaignId, () -> delegate.findSoldOutTtl(campaignId));
            if (result.isPresent()) {
                lastSoldOutHitNanos = System.nanoTime();
                lastSoldOutRemainingMillis = result.getAsLong();
            }
            return result;
        }

        @Override
        public void putSoldOutIfAbsent(Long campaignId, long ttlMillis) {
            logged("sold-out-put", null, campaignId, () -> {
                delegate.putSoldOutIfAbsent(campaignId, ttlMillis);
                return "done";
            });
        }

        private <T> T logged(String op, Long userId, Long campaignId, Supplier<T> call) {
            calls.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet();
            log.append(event(op + "-before", userId, campaignId));
            try {
                T result = call.get();
                log.append(event(op + "-result " + result, userId, campaignId));
                return result;
            } catch (RuntimeException e) {
                failures.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet();
                log.append(event(op + "-failed " + e.getClass().getSimpleName() + " (적용 여부 불명)", userId, campaignId));
                throw e;
            }
        }

        private Map<String, Object> event(String stage, Long userId, Long campaignId) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "cache " + stage);
            event.put("case", testCase);
            event.put("userId", String.valueOf(userId));
            event.put("campaignId", String.valueOf(campaignId));
            return event;
        }
    }

    @MockitoBean
    private CouponWaitingScheduler couponWaitingScheduler;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CouponFacade couponFacade;
    @Autowired
    private RecordingAdmissionRepository recording;
    @Autowired
    private RecordingCacheRepository cache;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;
    @Autowired
    private ApplicationContext applicationContext;

    private String containerId;
    private PauseGuard pauseGuard;
    private boolean finishedCleanly;
    private final List<String> ownKeys = new ArrayList<>();

    @BeforeEach
    void reset() {
        finishedCleanly = false;
        pauseGuard = new PauseGuard(CouponLocalRedisFaultIntegrationTest::docker, recording.log());
        CouponLocalRedisFaultIntegrationTest.limitHttpRead(rest);
    }

    @AfterEach
    void restoreRedis() throws Exception {
        try {
            CouponLocalRedisFaultIntegrationTest.endCase(pauseGuard, recording, finishedCleanly, couponFacade::close,
                databaseCleanUp::truncateAllTables);
        } finally {
            try {
                recording.requireNotWithheld();
            } catch (IllegalStateException e) {
                WITHHELD.add(e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 준비·관찰·정리

    /**
     * 앞 사례 보류 확인 → 닫기 → 대상 확인 → 사용자·운영 캠페인 commit → 대기실 5키·품절 키·사용자별 기발급 키가 모두 없을 때만 소유 → 명시 열기.
     * 키가 있으면 지우지 않고 시작 조건 실패다.
     */
    private List<Long> prepare(String testCase, int users, long quota) throws Exception {
        assertThat(WITHHELD).as("앞 사례의 정리 근거가 없으면 시작하지 않는다").isEmpty();
        recording.requireNotWithheld();
        cache.testCase(testCase);
        assertThat(couponFacade.close()).isFalse();
        containerId = CouponLocalRedisFaultIntegrationTest.verifyTarget(testCase, redisProperties, master, applicationContext, recording.log());
        pauseGuard.target(containerId);
        List<Long> ids = new ArrayList<>(fixture.users(users));
        List<String> keys = new ArrayList<>(CouponAdmissionRedisRepository.waitingKeys(OPERATING));
        keys.add(CouponCacheRedisRepository.soldOutKey(OPERATING));
        ids.forEach(user -> keys.add(CouponCacheRedisRepository.issuedKey(user, OPERATING)));
        long existing = CouponLocalRedisFaultIntegrationTest.observedKeys(containerId, keys);
        recording.log().append(Map.of("event", "start-keys", "case", testCase, "keys", keys, "existing", existing));
        assertThat(existing).as("시작 전 소유할 대기실·캐시 키가 없어야 한다").isZero();
        ownKeys.addAll(keys);
        ids.add(fixture.operatingCampaign(OPERATING, quota));
        assertThat(couponFacade.open()).isTrue();
        return ids;
    }

    private void markSoldOut(Long campaignId) {
        assertThat(jdbc.update("UPDATE coupon_campaign SET issued_count = total_quota WHERE id = ?", campaignId)).isEqualTo(1);
    }

    private long pttl(String key) throws Exception {
        return Long.parseLong(CouponLocalRedisFaultIntegrationTest.docker("exec", containerId, "redis-cli", "-n",
            String.valueOf(CouponLocalRedisFaultIntegrationTest.DATABASE), "PTTL", key).trim());
    }

    private static long campaignSql(List<String> statements) {
        return statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).contains("from coupon_campaign")).count();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(String testCase, Long userId, String waitingId) {
        recording.next(testCase, userId, OPERATING);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(CouponLocalRedisFaultIntegrationTest.as(userId).getHeaders());
        if (waitingId != null) {
            headers.set(CouponV1Controller.WAITING_ID_HEADER, waitingId);
        }
        return rest.exchange("/api/v1/coupon-campaigns/" + OPERATING + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers),
            CouponLocalRedisFaultIntegrationTest.COUPON);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> register(String testCase, Long userId) {
        recording.next(testCase, userId, OPERATING);
        return rest.exchange("/api/v1/coupon-campaigns/" + OPERATING + "/waiting", HttpMethod.POST,
            CouponLocalRedisFaultIntegrationTest.as(userId), CouponLocalRedisFaultIntegrationTest.WAITING);
    }

    /** 등록 → 입장 처리(자동 회차 없음) 뒤 waitingId. 장애 전 준비 단계다. */
    private String admit(String testCase, Long userId) {
        var registered = register(testCase, userId);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(couponFacade.processAdmissions()).isPresent();
        return registered.getBody().data().waitingId();
    }

    private void unpause(String testCase) throws Exception {
        pauseGuard.closeEntryAndRestore(testCase);
    }

    /**
     * 사례 끝 정리. 닫기 → 모든 HTTP 응답 수신(자동 회차 없음) → 최종 복구·불명 확인 → 같은 연결·서버 단언(키 삭제 전) → 소유 키만 삭제 →
     * 키 0·processing 0·DB 결과 보존 단언. 늦게 적용되는 명령은 같은 연결에서 삭제보다 앞선다. 이 단언까지 성공해야만 정상 종료다.
     */
    private void finishCase(String testCase, long beforeId, String beforeRunId, long issued, long coupons) throws Exception {
        couponFacade.close();
        assertThat(couponFacade.status()).isFalse();
        long endId = CouponLocalRedisFaultIntegrationTest.clientId(master);
        String endRunId = CouponLocalRedisFaultIntegrationTest.runId(master);
        recording.log().append(Map.of("event", "final-cleanup-before", "case", testCase, "clientId", endId, "runId", endRunId,
            "keys", CouponLocalRedisFaultIntegrationTest.observedKeys(containerId, ownKeys),
            "db", Map.of("issued", fixture.issuedCount(OPERATING), "coupons", fixture.couponCount(OPERATING))));
        pauseGuard.restore(testCase + " final");
        pauseGuard.requireResolved();
        assertThat(endId).as("종료 시점까지 같은 연결").isEqualTo(beforeId);
        assertThat(endRunId).as("종료 시점까지 같은 서버 프로세스").isEqualTo(beforeRunId);
        recording.cleanupStep(testCase);
        master.delete(ownKeys);
        long keysLeft = CouponLocalRedisFaultIntegrationTest.observedKeys(containerId, ownKeys);
        Set<String> processing = CouponLocalRedisFaultIntegrationTest.observedMembers(containerId);
        recording.log().append(Map.of("event", "final-cleanup-after", "case", testCase, "keys", keysLeft, "processing", processing,
            "db", Map.of("issued", fixture.issuedCount(OPERATING), "coupons", fixture.couponCount(OPERATING))));
        System.out.printf("[coupon-%s-end] keysLeft=%d processing=%s issued=%d coupons=%d clientId=%d runId=%s%n", testCase.toLowerCase(Locale.ROOT),
            keysLeft, processing, fixture.issuedCount(OPERATING), fixture.couponCount(OPERATING), endId, endRunId);
        assertAll(
            () -> assertThat(keysLeft).as("소유 대기실·캐시 키 모두 없음").isZero(),
            () -> assertThat(processing).isEmpty(),
            () -> assertThat(fixture.issuedCount(OPERATING)).as("키 정리가 발급 수를 바꾸지 않음").isEqualTo(issued),
            () -> assertThat(fixture.couponCount(OPERATING)).as("키 정리가 쿠폰을 바꾸지 않음").isEqualTo(coupons)
        );
        finishedCleanly = true;
    }

    // ------------------------------------------------------------------ R4-a 실제 issued get

    @DisplayName("R4-a cold: 기발급 키가 없을 때 Redis 가 멈추면 issued get 실패 뒤 DB 기존 결과 200·같은 couponId 이고 추가 발급 0·수용 유지다.")
    @Test
    void r4aColdIssuedGet() throws Exception {
        String testCase = "R4a-cold";
        List<Long> ids = prepare(testCase, 1, 10);
        Long owner = ids.get(0);
        Long couponId = fixture.storedCoupon(owner, OPERATING);
        assertThat(pttl(CouponCacheRedisRepository.issuedKey(owner, OPERATING))).as("cold: 키 없음").isEqualTo(-2);
        assertFallbackDuringPause(testCase, owner, couponId);
    }

    @DisplayName("R4-a warm: 정상 요청으로 기발급 키를 채운 뒤에도 Redis 가 멈추면 get 은 실패하며, DB 기존 결과 200·같은 couponId·추가 발급 0·수용 유지다.")
    @Test
    void r4aWarmIssuedGet() throws Exception {
        String testCase = "R4a-warm";
        List<Long> ids = prepare(testCase, 1, 10);
        Long owner = ids.get(0);
        Long couponId = fixture.storedCoupon(owner, OPERATING);
        var warm = issue(testCase, owner, null);
        long warmPttl = pttl(CouponCacheRedisRepository.issuedKey(owner, OPERATING));
        recording.log().append(Map.of("event", "warm-filled", "case", testCase, "status", warm.getStatusCode().value(), "pttl", warmPttl));
        assertThat(warm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(warmPttl).as("warm: 실제 Redis 에 TTL 있는 키").isPositive();
        assertFallbackDuringPause(testCase, owner, couponId);
    }

    /** R4-a 공통: pause 중 헤더 없는 발급 재요청. 실제 get 실패·DB 결과·put 시도 결과를 기록한다. warm hit 성공으로 쓰지 않는다. */
    private void assertFallbackDuringPause(String testCase, Long owner, Long couponId) throws Exception {
        long beforeId = CouponLocalRedisFaultIntegrationTest.clientId(master);
        String beforeRunId = CouponLocalRedisFaultIntegrationTest.runId(master);
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId));
        CouponLocalRedisFaultIntegrationTest.requireNoOtherClients(testCase, containerId, beforeId, recording.log());
        int getFailures = cache.failures("issued-get");
        int puts = cache.calls("issued-put");
        int putFailures = cache.failures("issued-put");
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> stored;
        boolean acceptingDuringFault;
        pauseGuard.pause(testCase);
        try {
            stored = issue(testCase, owner, null);
            acceptingDuringFault = couponFacade.status();
        } finally {
            unpause(testCase);
        }
        long pttlAfter = pttl(CouponCacheRedisRepository.issuedKey(owner, OPERATING));
        recording.log().append(Map.of("event", "after", "case", testCase, "status", stored.getStatusCode().value(),
            "issuedGetFailures", cache.failures("issued-get") - getFailures, "issuedPuts", cache.calls("issued-put") - puts,
            "issuedPutFailures", cache.failures("issued-put") - putFailures, "issuedKeyPttlAfterUnpause", pttlAfter,
            "accepting", acceptingDuringFault));
        System.out.printf("[coupon-%s] status=%s getFailures=%d puts=%d putFailures=%d pttlAfterUnpause=%d accepting=%s%n",
            testCase.toLowerCase(Locale.ROOT), stored.getStatusCode(), cache.failures("issued-get") - getFailures,
            cache.calls("issued-put") - puts, cache.failures("issued-put") - putFailures, pttlAfter, acceptingDuringFault);
        assertAll(
            () -> assertThat(cache.failures("issued-get") - getFailures).as("실제 issued get 실패").isEqualTo(1),
            () -> assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(stored.getBody().data().couponId()).isEqualTo(couponId),
            () -> assertThat(acceptingDuringFault).as("cache 실패만으로 닫지 않음").isTrue(),
            () -> assertThat(fixture.couponCount(OPERATING, owner)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(1)
        );
        finishCase(testCase, beforeId, beforeRunId, 1, 1);
    }

    // ------------------------------------------------------------------ R4-b 실제 cache + START

    @DisplayName("R4-b: READY 사용자의 발급 중 Redis 가 멈추면 issued get 실패 → DB 없음 → 실제 start 실패로 503·닫힘이고 신규 DB 효과 0 이다.")
    @Test
    void r4bCacheThenStart() throws Exception {
        String testCase = "R4b";
        List<Long> ids = prepare(testCase, 1, 10);
        Long newcomer = ids.get(0);
        String waitingId = admit(testCase, newcomer);
        long beforeId = CouponLocalRedisFaultIntegrationTest.clientId(master);
        String beforeRunId = CouponLocalRedisFaultIntegrationTest.runId(master);
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId));
        CouponLocalRedisFaultIntegrationTest.requireNoOtherClients(testCase, containerId, beforeId, recording.log());
        int getFailures = cache.failures("issued-get");
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> rejected;
        boolean closedDuringFault;
        long couponsDuringFault;
        pauseGuard.pause(testCase);
        try {
            rejected = issue(testCase, newcomer, waitingId);
            closedDuringFault = !couponFacade.status();
            couponsDuringFault = fixture.couponCount(OPERATING, newcomer);
        } finally {
            unpause(testCase);
        }
        Set<String> lateProcessing = CouponLocalRedisFaultIntegrationTest.observedMembers(containerId);
        recording.log().append(Map.of("event", "after", "case", testCase, "status", rejected.getStatusCode().value(),
            "issuedGetFailures", cache.failures("issued-get") - getFailures, "startWaitingId", String.valueOf(recording.waitingIdOf(newcomer)),
            "processingAfterUnpause", lateProcessing, "issuedPuts", cache.calls("issued-put")));
        System.out.printf("[coupon-r4b] status=%s getFailures=%d closed=%s lateProcessing=%s coupons=%d%n", rejected.getStatusCode(),
            cache.failures("issued-get") - getFailures, closedDuringFault, lateProcessing, couponsDuringFault);
        assertAll(
            () -> assertThat(cache.failures("issued-get") - getFailures).as("cache 실패").isEqualTo(1),
            () -> assertThat(recording.waitingIdOf(newcomer)).as("상태 Redis start 위임").isEqualTo(waitingId),
            () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(rejected.getBody().meta().errorCode()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"),
            () -> assertThat(closedDuringFault).as("상태 Redis 실패로 닫힘").isTrue(),
            () -> assertThat(couponsDuringFault).isZero(),
            () -> assertThat(fixture.issuedCount(OPERATING)).isZero(),
            () -> assertThat(cache.calls("issued-put")).as("positive 결과 없음").isZero()
        );
        finishCase(testCase, beforeId, beforeRunId, 0, 0);
    }

    // ------------------------------------------------------------------ R4-c 실제 issued put

    @DisplayName("R4-c: finish 정상 완료 뒤 첫 기발급 put 직전에만 Redis 를 멈추면 put 은 실패해도 201·쿠폰 1·점유 0 이고 cache 실패만으로 닫지 않는다.")
    @Test
    void r4cIssuedPutAfterFinish() throws Exception {
        String testCase = "R4c";
        List<Long> ids = prepare(testCase, 1, 10);
        Long user = ids.get(0);
        String waitingId = admit(testCase, user);
        long beforeId = CouponLocalRedisFaultIntegrationTest.clientId(master);
        String beforeRunId = CouponLocalRedisFaultIntegrationTest.runId(master);
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId));
        CouponLocalRedisFaultIntegrationTest.requireNoOtherClients(testCase, containerId, beforeId, recording.log());
        AtomicReference<String> injection = new AtomicReference<>("미도달");
        PauseGuard guard = pauseGuard;
        cache.beforeFirstIssuedPut(user, OPERATING, () -> {
            String finish = recording.finishOutcomeOf(waitingId);
            boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            if (!"APPLIED".equals(finish) || inTransaction) {
                injection.set("조건 불충족 finish=" + finish + " tx=" + inTransaction); // pause 하지 않는다.
                recording.log().append(Map.of("event", "put-injection-skipped", "case", testCase, "finish", String.valueOf(finish),
                    "tx", inTransaction));
                return;
            }
            injection.set("주입");
            guard.pauseInRequest(testCase);
        });
        int putFailures = cache.failures("issued-put");
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> created;
        try {
            created = issue(testCase, user, waitingId);
        } finally {
            unpause(testCase);
        }
        boolean accepting = couponFacade.status();
        Set<String> processing = CouponLocalRedisFaultIntegrationTest.observedMembers(containerId);
        long pttlAfter = pttl(CouponCacheRedisRepository.issuedKey(user, OPERATING));
        recording.log().append(Map.of("event", "after", "case", testCase, "status", created.getStatusCode().value(),
            "injection", injection.get(), "finish", String.valueOf(recording.finishOutcomeOf(waitingId)),
            "issuedPutFailures", cache.failures("issued-put") - putFailures, "issuedKeyPttlAfterUnpause", pttlAfter,
            "processing", processing, "accepting", accepting));
        System.out.printf("[coupon-r4c] status=%s injection=%s finish=%s putFailures=%d pttlAfterUnpause=%d processing=%s accepting=%s%n",
            created.getStatusCode(), injection.get(), recording.finishOutcomeOf(waitingId), cache.failures("issued-put") - putFailures,
            pttlAfter, processing, accepting);
        assertAll(
            () -> assertThat(injection.get()).as("finish APPLIED·TX 밖에서만 주입").isEqualTo("주입"),
            () -> assertThat(cache.failures("issued-put") - putFailures).as("실제 put 실패").isEqualTo(1),
            () -> assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(fixture.couponCount(OPERATING, user)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(1),
            () -> assertThat(processing).as("정상 finish 로 점유 0").isEmpty(),
            () -> assertThat(accepting).as("cache 실패만으로 닫지 않음").isTrue()
        );
        finishCase(testCase, beforeId, beforeRunId, 1, 1);
    }

    // ------------------------------------------------------------------ R5 로컬 품절

    /** DB 품절 → 첫 등록이 Redis true 저장 → 둘째 등록이 실제 Redis hit 으로 로컬을 채운다. 둘째 응답 수신 시각(단조)을 돌려준다. */
    private long fillRedisAndLocal(String testCase, Long first, Long second) throws Exception {
        markSoldOut(OPERATING);
        var stored = register(testCase, first);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cache.calls("sold-out-put")).isEqualTo(1);
        long redisPttl = pttl(CouponCacheRedisRepository.soldOutKey(OPERATING));
        int hits = cache.calls("sold-out-get");
        var filled = register(testCase, second);
        long filledAt = System.nanoTime();
        recording.log().append(Map.of("event", "local-filled", "case", testCase, "redisPttlAfterPut", redisPttl,
            "remainingAtHitMillis", cache.lastSoldOutRemainingMillis(), "status", filled.getStatusCode().value()));
        assertThat(filled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cache.calls("sold-out-get")).as("둘째 등록은 실제 Redis 를 읽었다").isEqualTo(hits + 1);
        assertThat(cache.lastSoldOutRemainingMillis()).as("로컬 수명이 Redis 잔여로 줄지 않을 만큼 남음")
            .isGreaterThan(LOCAL_SOLD_OUT_TTL.toMillis());
        return filledAt;
    }

    @DisplayName("R5-a: 실제 Redis hit 으로 채운 로컬 품절이 유효한 동안 Redis 가 멈추면, 등록은 품절 Redis 조회 0·캠페인 SQL 0 으로 409 다. 선행 issued get 실패는 따로 기록한다.")
    @Test
    void r5aLocalSoldOutValid() throws Exception {
        String testCase = "R5a";
        List<Long> ids = prepare(testCase, 3, 1);
        long filledResponseAt = fillRedisAndLocal(testCase, ids.get(0), ids.get(1));
        long beforeId = CouponLocalRedisFaultIntegrationTest.clientId(master);
        String beforeRunId = CouponLocalRedisFaultIntegrationTest.runId(master);
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId));
        CouponLocalRedisFaultIntegrationTest.requireNoOtherClients(testCase, containerId, beforeId, recording.log());
        int soldOutGets = cache.calls("sold-out-get");
        int issuedGetFailures = cache.failures("issued-get");
        ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> rejected;
        long respondedAt;
        List<String> statements;
        pauseGuard.pause(testCase);
        try {
            SqlCapture.start();
            rejected = register(testCase, ids.get(2));
            respondedAt = System.nanoTime();
        } finally {
            statements = SqlCapture.stop();
            unpause(testCase);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(respondedAt - cache.lastSoldOutHitNanos());
        recording.log().append(Map.of("event", "after", "case", testCase, "status", rejected.getStatusCode().value(),
            "soldOutGets", cache.calls("sold-out-get") - soldOutGets, "campaignSql", campaignSql(statements),
            "issuedGetFailures", cache.failures("issued-get") - issuedGetFailures, "sqlCount", statements.size(),
            "elapsedSinceHitMillis", elapsedMillis, "hitToFillResponseMillis", TimeUnit.NANOSECONDS.toMillis(filledResponseAt - cache.lastSoldOutHitNanos())));
        System.out.printf("[coupon-r5a] status=%s soldOutGets=%d campaignSql=%d issuedGetFailures=%d sql=%d elapsedSinceHit=%dms%n",
            rejected.getStatusCode(), cache.calls("sold-out-get") - soldOutGets, campaignSql(statements),
            cache.failures("issued-get") - issuedGetFailures, statements.size(), elapsedMillis);
        assertAll(
            () -> assertThat(elapsedMillis).as("로컬 진입이 실제 수명 안(보수적 상한: Redis hit→응답)").isLessThan(LOCAL_SOLD_OUT_TTL.toMillis()),
            () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
            () -> assertThat(rejected.getBody().meta().errorCode()).isEqualTo("COUPON_CAMPAIGN_SOLD_OUT"),
            () -> assertThat(cache.calls("sold-out-get") - soldOutGets).as("품절 Redis 조회 0").isZero(),
            () -> assertThat(campaignSql(statements)).as("캠페인 SQL 0").isZero(),
            () -> assertThat(cache.failures("issued-get") - issuedGetFailures).as("선행 issued get 실제 실패").isEqualTo(1),
            () -> assertThat(couponFacade.status()).isTrue()
        );
        finishCase(testCase, beforeId, beforeRunId, 1, 0);
    }

    @DisplayName("R5-b: 로컬 품절 수명(5초)이 지난 뒤 Redis 가 멈추면, 품절 Redis get 실패 → 캠페인 DB 품절 → 품절 put 실패로 409 이고 새 대기 등록 0 이다.")
    @Test
    void r5bLocalSoldOutExpired() throws Exception {
        String testCase = "R5b";
        List<Long> ids = prepare(testCase, 3, 1);
        long filledAt = fillRedisAndLocal(testCase, ids.get(0), ids.get(1));
        Duration wait = LOCAL_SOLD_OUT_TTL.plusMillis(500);
        long until = filledAt + wait.toNanos();
        while (System.nanoTime() < until) {
            Thread.sleep(50);
        }
        long localExpiredMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - filledAt);
        long redisPttl = pttl(CouponCacheRedisRepository.soldOutKey(OPERATING));
        long beforeId = CouponLocalRedisFaultIntegrationTest.clientId(master);
        String beforeRunId = CouponLocalRedisFaultIntegrationTest.runId(master);
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId,
            "sinceLocalFillMillis", localExpiredMillis, "redisSoldOutPttl", redisPttl));
        CouponLocalRedisFaultIntegrationTest.requireNoOtherClients(testCase, containerId, beforeId, recording.log());
        int soldOutGets = cache.calls("sold-out-get");
        int soldOutGetFailures = cache.failures("sold-out-get");
        int soldOutPuts = cache.calls("sold-out-put");
        int soldOutPutFailures = cache.failures("sold-out-put");
        ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> rejected;
        List<String> statements;
        pauseGuard.pause(testCase);
        try {
            SqlCapture.start();
            rejected = register(testCase, ids.get(2));
        } finally {
            statements = SqlCapture.stop();
            unpause(testCase);
        }
        long waitingKeys = CouponLocalRedisFaultIntegrationTest.observedKeys(containerId, CouponAdmissionRedisRepository.waitingKeys(OPERATING));
        recording.log().append(Map.of("event", "after", "case", testCase, "status", rejected.getStatusCode().value(),
            "soldOutGets", cache.calls("sold-out-get") - soldOutGets, "soldOutGetFailures", cache.failures("sold-out-get") - soldOutGetFailures,
            "soldOutPuts", cache.calls("sold-out-put") - soldOutPuts, "soldOutPutFailures", cache.failures("sold-out-put") - soldOutPutFailures,
            "campaignSql", campaignSql(statements), "waitingKeysAfter", waitingKeys));
        System.out.printf("[coupon-r5b] status=%s sinceFill=%dms soldOutGetFailures=%d soldOutPutFailures=%d campaignSql=%d waitingKeys=%d%n",
            rejected.getStatusCode(), localExpiredMillis, cache.failures("sold-out-get") - soldOutGetFailures,
            cache.failures("sold-out-put") - soldOutPutFailures, campaignSql(statements), waitingKeys);
        assertAll(
            () -> assertThat(localExpiredMillis).as("로컬 수명 경과").isGreaterThanOrEqualTo(LOCAL_SOLD_OUT_TTL.toMillis()),
            () -> assertThat(redisPttl).as("Redis 품절 키는 아직 유효").isPositive(),
            () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
            () -> assertThat(rejected.getBody().meta().errorCode()).isEqualTo("COUPON_CAMPAIGN_SOLD_OUT"),
            () -> assertThat(cache.calls("sold-out-get") - soldOutGets).as("로컬 miss 로 Redis 조회").isEqualTo(1),
            () -> assertThat(cache.failures("sold-out-get") - soldOutGetFailures).as("품절 get 실제 실패").isEqualTo(1),
            () -> assertThat(campaignSql(statements)).as("캠페인 DB 품절 확인").isPositive(),
            () -> assertThat(cache.calls("sold-out-put") - soldOutPuts).isEqualTo(1),
            () -> assertThat(cache.failures("sold-out-put") - soldOutPutFailures).as("품절 put 실제 실패").isEqualTo(1),
            () -> assertThat(waitingKeys).as("새 대기 등록 0").isZero(),
            () -> assertThat(couponFacade.status()).isTrue()
        );
        finishCase(testCase, beforeId, beforeRunId, 1, 0);
    }
}
