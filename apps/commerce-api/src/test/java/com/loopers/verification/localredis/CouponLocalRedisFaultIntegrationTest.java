package com.loopers.verification.localredis;

import com.loopers.fixture.CouponCacheMiss;
import org.springframework.context.annotation.Import;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.interfaces.scheduler.CouponWaitingScheduler;
import com.loopers.CommerceApiApplication;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.interfaces.api.coupon.CouponV1Controller;
import com.loopers.fixture.CouponFixture;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 현재 실행 중인 로컬 Redis(redis-master 컨테이너)를 pause/unpause 해 Q9(실행 시작 장애)·Q10(종료 기록 장애)을 대기실 경로로 확인한다
 * (상세설계 §10.1·§12.5). 기본 token 자리·cleanup API 는 확장에서 제거됐다. 장애 뒤 정리는 DB 결과·같은 연결 경과를 확인한 뒤
 * 이 사례 운영 캠페인의 대기실 키를 무효화하는 test 단계이며, 장애별 수동 재개 절차의 검증이 아니다(후속 범위).
 * 기본 실행에서는 비활성이며 {@code COUPON_LOCAL_REDIS_FAULT_TEST=true} 와 {@code COUPON_LOCAL_REDIS_FAULT_LOG}(절대 경로)를 준 명시 실행만 한다.
 *
 * <p>부트스트랩은 fixture Redis 컨테이너 구성과 앱 진입 클래스를 스캔에서 빼고, 실제 로컬 master/replica/database 를 속성으로 넣는다.
 * MySQL 은 기존 테스트 컨테이너를 쓴다. 대기실 decorator 는 Redis 명령을 보내기 전에 JSONL 기록을 append·flush 한다.
 * 실패해 정상 정리 근거가 없으면 대기실 키를 지우지 않고 기록만 남긴다(§10.1 사후 수동 정리 절차 대상). Redis 는 pause 상태로 두지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "COUPON_LOCAL_REDIS_FAULT_TEST", matches = "true")
@SpringBootTest(classes = CouponLocalRedisFaultIntegrationTest.Bootstrap.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("로컬 Redis 장애: 시작 실패는 503·닫힘·DB 미진입, 종료 기록 실패는 201 유지·닫힘이며, 같은 연결의 후속 응답과 종료 확인 뒤 자기 대기실 키만 정리한다.")
class CouponLocalRedisFaultIntegrationTest {

    static final long OPERATING = 9_101L;
    static final String MASTER_HOST = env("COUPON_LOCAL_REDIS_MASTER_HOST", "localhost");
    static final int MASTER_PORT = Integer.parseInt(env("COUPON_LOCAL_REDIS_MASTER_PORT", "6379"));
    static final String REPLICA_HOST = env("COUPON_LOCAL_REDIS_REPLICA_HOST", "localhost");
    static final int REPLICA_PORT = Integer.parseInt(env("COUPON_LOCAL_REDIS_REPLICA_PORT", "6380"));
    static final int DATABASE = Integer.parseInt(env("COUPON_LOCAL_REDIS_DATABASE", "0"));
    private static final long WAIT_SECONDS = 5;

    static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.WaitingResponse>> WAITING =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ConfigurationPropertiesScan("com.loopers")
    @Import(CouponCacheMiss.class) // 캐시 도입 전 계약 검사. 조회 miss·저장 no-op(설계 §13.12 C1)
    @ComponentScan(basePackages = "com.loopers", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {RedisTestContainersConfig.class, CommerceApiApplication.class})
    })
    static class Bootstrap {
        @Bean
        @Primary
        RecordingAdmissionRepository recordingAdmissionRepository(CouponAdmissionRedisRepository redisRepository) {
            return new RecordingAdmissionRepository(redisRepository, FaultLog.open());
        }
    }

    @DynamicPropertySource
    static void pinToLocalRedis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.database", () -> String.valueOf(DATABASE));
        registry.add("datasource.redis.master.host", () -> MASTER_HOST);
        registry.add("datasource.redis.master.port", () -> String.valueOf(MASTER_PORT));
        registry.add("datasource.redis.replicas[0].host", () -> REPLICA_HOST);
        registry.add("datasource.redis.replicas[0].port", () -> String.valueOf(REPLICA_PORT));
        registry.add("commerce.coupon.waiting.campaign-id", () -> String.valueOf(OPERATING));
    }

    /** UTF-8 JSONL 실행 기록. 줄마다 append·DSYNC 쓰기를 마친 뒤 반환한다. 실패하면 예외로 다음 단계를 막는다. */
    static final class FaultLog {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final Path path;

        private FaultLog(Path path) {
            this.path = path;
        }

        /** 경로를 직접 받는 생성. 대역 단위 테스트가 임시 파일로 쓴다. */
        static FaultLog at(Path path) {
            return new FaultLog(path);
        }

        static FaultLog open() {
            String raw = System.getenv("COUPON_LOCAL_REDIS_FAULT_LOG");
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("COUPON_LOCAL_REDIS_FAULT_LOG 가 없다. 장치 준비 실패.");
            }
            Path path = Path.of(raw);
            if (!path.isAbsolute()) {
                throw new IllegalStateException("COUPON_LOCAL_REDIS_FAULT_LOG 는 절대 경로여야 한다: " + raw);
            }
            FaultLog log = new FaultLog(path);
            log.append(Map.of("event", "log-open", "path", path.toString()));
            return log;
        }

        synchronized void append(Map<String, ?> fields) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", Instant.now().toString());
            line.put("jvmPid", ProcessHandle.current().pid());
            line.put("jvmStart", ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("불명"));
            line.putAll(fields);
            try {
                Files.writeString(path, MAPPER.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.DSYNC);
            } catch (IOException e) {
                throw new IllegalStateException("실행 기록 쓰기 실패: " + path, e);
            }
        }

        Path path() {
            return path;
        }
    }

    /**
     * 실제 Redis 저장소에 위임하는 test decorator. 명령을 보내기 전에 기록을 남기고, 기록이 실패하면 위임하지 않는다.
     * 순차 요청마다 테스트가 지정한 사용자·캠페인을 함께 기록한다. Q10 은 finish 위임 직전에 한 번 동작을 끼운다.
     */
    static final class RecordingAdmissionRepository implements CouponAdmissionRepository {
        private final CouponAdmissionRepository delegate;
        private final FaultLog log;
        private final Map<String, String> waitingIdByUser = new ConcurrentHashMap<>();
        private final Map<String, String> finishOutcome = new ConcurrentHashMap<>();
        private final AtomicReference<Runnable> beforeFinish = new AtomicReference<>();
        private final List<String> withheld = Collections.synchronizedList(new ArrayList<>());
        private volatile Map<String, Object> context = Map.of("userId", "불명", "campaignId", "불명", "case", "불명");

        RecordingAdmissionRepository(CouponAdmissionRepository delegate, FaultLog log) {
            this.delegate = delegate;
            this.log = log;
        }

        void next(String testCase, Long userId, Long campaignId) {
            context = Map.of("case", testCase, "userId", userId, "campaignId", campaignId);
        }

        /** 제품 명령이 아닌 test 의 대기실 키 무효화 단계. */
        void cleanupStep(String testCase) {
            context = Map.of("case", testCase, "userId", "정리 단계(사용자 없음)", "campaignId", OPERATING);
        }

        void beforeNextFinish(Runnable action) {
            beforeFinish.set(action);
        }

        /** 사례 끝에 쓰이지 않은 callback 을 비워 다음 사례·재개가 소비하지 않게 한다. */
        void clearBeforeFinish(String testCase) {
            if (beforeFinish.getAndSet(null) != null) {
                log.append(Map.of("event", "finish-callback-cleared", "case", testCase));
            }
        }

        String waitingIdOf(Long userId) {
            return waitingIdByUser.get(String.valueOf(userId));
        }

        String finishOutcomeOf(String waitingId) {
            return finishOutcome.get(waitingId);
        }

        FaultLog log() {
            return log;
        }

        /**
         * 정상 종료·정리 근거를 얻지 못한 사례를 컨텍스트 수명 동안 남긴다. 실패한 요청이 아직 start 전일 수 있으므로
         * 빈 Set·다른 요청의 성공으로 풀지 않는다. 새 컨텍스트는 새 bean 이다.
         */
        void withhold(String reason) {
            withheld.add(reason);
            log.append(Map.of("event", "context-withheld", "reason", reason));
        }

        /** 이후 사례의 준비·DB 변경·수용 열기·키 정리 전에 호출한다. */
        void requireNotWithheld() {
            synchronized (withheld) {
                if (!withheld.isEmpty()) {
                    throw new IllegalStateException("이 컨텍스트는 앞 사례의 종료·정리 근거가 없어 보류됐다. " + withheld);
                }
            }
        }

        @Override
        public Registration register(Long campaignId, Long userId, String newWaitingId) {
            return logged("register", newWaitingId, () -> delegate.register(campaignId, userId, newWaitingId));
        }

        @Override
        public Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
            return logged("find", null, () -> delegate.find(campaignId, userId));
        }

        @Override
        public AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit) {
            return logged("process-admissions", null,
                () -> delegate.processAdmissions(campaignId, capacity, admissionDurationMillis, soldOut, canAdmit));
        }

        @Override
        public Start start(Long campaignId, Long userId, String waitingId) {
            waitingIdByUser.put(String.valueOf(userId), waitingId);
            return logged("start", waitingId, () -> delegate.start(campaignId, userId, waitingId));
        }

        @Override
        public FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
            Map<String, Object> ctx = context;
            log.append(event("finish-before " + outcome, waitingId, ctx));
            Runnable action = beforeFinish.getAndSet(null);
            if (action != null) {
                action.run();
            }
            try {
                FinishOutcome result = delegate.finish(campaignId, userId, waitingId, outcome);
                finishOutcome.put(waitingId, String.valueOf(result));
                log.append(event("finish-result " + result, waitingId, ctx));
                return result;
            } catch (RuntimeException e) {
                finishOutcome.put(waitingId, "failed " + e.getClass().getSimpleName());
                log.append(event("finish-failed " + e.getClass().getSimpleName() + " (적용 여부 불명)", waitingId, ctx));
                throw e;
            }
        }

        private <T> T logged(String stage, String waitingId, java.util.function.Supplier<T> call) {
            Map<String, Object> ctx = context;
            log.append(event(stage + "-before", waitingId, ctx));
            try {
                T result = call.get();
                log.append(event(stage + "-result " + result, waitingId, ctx));
                return result;
            } catch (RuntimeException e) {
                log.append(event(stage + "-failed " + e.getClass().getSimpleName() + " (적용 여부 불명)", waitingId, ctx));
                throw e;
            }
        }

        private static Map<String, Object> event(String stage, String waitingId, Map<String, Object> ctx) {
            Map<String, Object> event = new LinkedHashMap<>(ctx);
            event.put("event", stage);
            event.put("waitingId", String.valueOf(waitingId));
            event.put("endpoint", MASTER_HOST + ":" + MASTER_PORT);
            event.put("database", DATABASE);
            event.put("keys", CouponAdmissionRedisRepository.waitingKeys(OPERATING));
            return event;
        }
    }

    /**
     * 확인된 대상 컨테이너 하나의 pause 시도와 실제 상태 복구. pause 를 "시도했다"는 사실을 명령 전에 남기고, 복구는 CLI 결과가 아니라
     * 그 컨테이너의 실제 Paused 상태를 조회해 판단한다. 대상이 정해지기 전이나 다른 컨테이너는 조작하지 않는다.
     *
     * <p>요청 안 pause(Q10)는 진입 문으로 조율한다. 문이 닫힌 뒤 도착한 호출은 pause 없이 기록만 하고 정상 반환해 원래 위임을 막지 않는다.
     * 이미 진입한 호출은 finally 에서 종료 신호를 남기고, 복구는 그 신호를 유한하게 기다린 뒤에만 상태를 조회한다.
     * 시도 이력은 지우지 않아 서버 요청 종료 뒤의 최종 복구도 실제로 조회한다. pause 명령 실패·종료 신호 미확인·조회/unpause 실패는
     * 해소되지 않는 불명으로 남기며, 그 뒤 한 번의 조회가 false 여도 정리·재개를 허용하지 않는다.
     */
    static final class PauseGuard {
        /** 요청 안 pause 호출의 종료 신호 대기. Docker CLI 한 호출 한도(30초)보다 길다. */
        static final Duration PAUSE_END_WAIT = Duration.ofSeconds(35);
        static final Duration CLI_LIMIT = Duration.ofSeconds(30);
        /** test HTTP 클라이언트의 read 한도. 시간 초과는 관찰 실패이지 서버 요청 종료가 아니다. */
        static final Duration HTTP_READ = Duration.ofSeconds(60);

        interface Cli {
            String run(String... args) throws Exception;
        }

        private final Cli cli;
        private final FaultLog log;
        private final Duration pauseEndWait;
        private final Object gate = new Object();
        private volatile String target;
        private volatile boolean attempted;
        private volatile String unresolved;
        private boolean entryClosed;
        private CountDownLatch inFlight;
        private final List<RuntimeException> logFailures = Collections.synchronizedList(new ArrayList<>());

        PauseGuard(Cli cli, FaultLog log) {
            this(cli, log, PAUSE_END_WAIT);
        }

        PauseGuard(Cli cli, FaultLog log, Duration pauseEndWait) {
            this.cli = cli;
            this.log = log;
            this.pauseEndWait = pauseEndWait;
        }

        /** HTTP read 한도는 종료 신호 대기와 Redis command timeout 의 합보다 커야 한다. 모르는 command timeout 은 거절한다. */
        static void requireTimeoutBudget(Duration commandTimeout) {
            if (commandTimeout == null || PAUSE_END_WAIT.compareTo(CLI_LIMIT) <= 0
                || HTTP_READ.compareTo(PAUSE_END_WAIT.plus(commandTimeout)) <= 0) {
                throw new IllegalStateException("대기 한도 조건 불충족: HTTP " + HTTP_READ + " > pause 종료 " + PAUSE_END_WAIT
                    + "(> CLI " + CLI_LIMIT + ") + command timeout " + commandTimeout);
            }
        }

        void target(String verifiedContainerId) {
            this.target = verifiedContainerId;
        }

        void pause(String testCase) throws Exception {
            if (target == null) {
                throw new IllegalStateException("확인된 대상 컨테이너 없이 pause 하지 않는다.");
            }
            attempted = true; // CLI 가 실패·대기 초과해도 실제로 멈췄을 수 있다.
            try {
                log.append(Map.of("event", "pause-attempt", "case", testCase, "container", target));
            } catch (RuntimeException e) {
                unresolved(testCase, "pause 시도 기록 실패 — pause 하지 않음");
                throw e; // 시도를 기록하지 못하면 pause 하지 않는다.
            }
            try {
                cli.run("pause", target);
            } catch (Exception e) {
                unresolved(testCase, "pause 명령 실패 — 원격 적용 불명: " + e.getMessage());
                throw e;
            }
            record(Map.of("event", "pause", "case", testCase, "container", target));
        }

        /** 요청 처리 스레드의 callback 용. 문이 닫혔으면 pause 하지 않고 정상 반환한다. */
        void pauseInRequest(String testCase) {
            CountDownLatch done;
            synchronized (gate) {
                if (entryClosed) {
                    record(Map.of("event", "pause-skipped-entry-closed", "case", testCase));
                    return;
                }
                done = new CountDownLatch(1);
                inFlight = done;
            }
            String outcome = "불명";
            try {
                pause(testCase);
                outcome = "성공";
            } catch (Exception e) {
                outcome = "실패 " + e.getClass().getSimpleName();
                throw new IllegalStateException("종료 기록 직전 pause 실패", e);
            } finally {
                try {
                    record(Map.of("event", "pause-call-end", "case", testCase, "outcome", outcome));
                } finally {
                    done.countDown(); // 호출 종료 신호. 종료 기록 실패는 record 가 불명으로 따로 남긴다.
                }
            }
        }

        /**
         * 진입 문을 닫고, 이미 진입한 pause 의 종료 신호를 유한하게 기다린 뒤 실제 상태로 복구한다.
         * 대기 중 interrupt 는 종료 미확인으로 남기고 복구를 계속한 뒤 interrupt 상태를 되돌린다(복구 CLI 대기가 즉시 끊기지 않게 복구 뒤에).
         */
        void closeEntryAndRestore(String testCase) throws Exception {
            CountDownLatch done;
            synchronized (gate) {
                entryClosed = true;
                done = inFlight;
            }
            record(Map.of("event", "pause-entry-closed", "case", testCase, "inFlight", done != null));
            boolean interrupted = false;
            if (done != null) {
                try {
                    if (!done.await(pauseEndWait.toMillis(), TimeUnit.MILLISECONDS)) {
                        unresolved(testCase, "진행 중 pause 의 종료 신호를 " + pauseEndWait + " 안에 확인하지 못함");
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    unresolved(testCase, "진행 중 pause 의 종료 대기 interrupt — 종료 미확인");
                }
            }
            Exception failure = null;
            try {
                restoreState(testCase);
            } catch (Exception e) {
                failure = e;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            reportFailures(failure);
        }

        /** pause 를 시도한 적이 있으면 매번 실제 상태를 조회해 멈춰 있으면 unpause 한다. 조회·복구·기록 실패는 복구 시도 뒤 던진다. */
        void restore(String testCase) throws Exception {
            Exception failure = null;
            try {
                restoreState(testCase);
            } catch (Exception e) {
                failure = e;
            }
            reportFailures(failure);
        }

        private void restoreState(String testCase) throws Exception {
            if (!attempted) {
                return;
            }
            synchronized (gate) {
                if (inFlight != null && inFlight.getCount() > 0) {
                    unresolved(testCase, "진행 중 pause 의 종료 전에 복구를 시작함");
                }
            }
            String paused;
            try {
                paused = cli.run("inspect", "-f", "{{.State.Paused}}", target).trim();
            } catch (Exception e) {
                unresolved(testCase, "pause 시도 뒤 상태 조회 실패");
                record(Map.of("event", "restore-state-unknown", "case", testCase, "container", target, "error", String.valueOf(e.getMessage())));
                throw new IllegalStateException("pause 시도 뒤 컨테이너 상태를 확인하지 못했다: " + target, e);
            }
            if ("true".equals(paused)) {
                try {
                    cli.run("unpause", target);
                } catch (Exception e) {
                    unresolved(testCase, "unpause 실패");
                    record(Map.of("event", "unpause-failed", "case", testCase, "container", target, "error", String.valueOf(e.getMessage())));
                    throw new IllegalStateException("unpause 실패: " + target, e);
                }
                record(Map.of("event", "unpause", "case", testCase, "container", target));
            } else {
                record(Map.of("event", "restore-not-paused", "case", testCase, "container", target, "paused", paused));
            }
        }

        /** 정리·재개 전에 호출한다. 불명이 한 번이라도 있었으면 던진다. */
        void requireResolved() {
            if (unresolved != null) {
                throw new IllegalStateException("pause 상태 불명 — 정리·재개 보류: " + unresolved);
            }
        }

        boolean resolved() {
            return unresolved == null;
        }

        /** 메모리의 불명 판정을 기록보다 먼저 남긴다. */
        private void unresolved(String testCase, String reason) {
            if (unresolved == null) {
                unresolved = reason;
            }
            record(Map.of("event", "pause-state-unresolved", "case", testCase, "reason", reason));
        }

        /** 기록 실패는 복구를 막지 않게 여기서 던지지 않는다. 불명으로 남기고 모아 두었다가 복구 시도 뒤 reportFailures 가 던진다. */
        private void record(Map<String, ?> fields) {
            try {
                log.append(fields);
            } catch (RuntimeException e) {
                if (unresolved == null) {
                    unresolved = "실행 기록 쓰기 실패: " + fields.get("event");
                }
                logFailures.add(e);
            }
        }

        /** 복구 실패를 먼저, 없으면 첫 기록 실패를 원인으로 던진다. 나머지 기록 실패는 suppressed 로 보존한다. */
        private void reportFailures(Exception failure) throws Exception {
            List<RuntimeException> logs;
            synchronized (logFailures) {
                logs = List.copyOf(logFailures);
                logFailures.clear();
            }
            if (failure == null && logs.isEmpty()) {
                return;
            }
            Exception thrown = failure != null ? failure : new IllegalStateException("실행 기록 쓰기 실패 — pause 상태 불명", logs.get(0));
            logs.stream().filter(e -> e != thrown.getCause()).forEach(thrown::addSuppressed);
            throw thrown;
        }
    }

    /**
     * 사례 끝. 남은 finish callback 을 비우고 진입 문을 닫은 뒤 복구한다. 사례가 정상 종료했고 pause 상태가 해소됐을 때만 DB 를 정리하며,
     * 그 밖에는 컨텍스트를 보류한다. 복구 실패도 정리보다 먼저 남기고 마지막에 던진다.
     */
    static void endCase(PauseGuard guard, RecordingAdmissionRepository recording, boolean finishedCleanly,
                        Runnable close, Runnable truncate) throws Exception {
        List<Exception> failures = new ArrayList<>();
        try {
            recording.clearBeforeFinish("after-each"); // callback 은 기록 전에 비워진다.
        } catch (RuntimeException e) {
            failures.add(e);
        }
        try {
            guard.closeEntryAndRestore("after-each"); // 보류된 컨텍스트에서도 Redis 복구 시도는 계속한다.
        } catch (Exception e) {
            failures.add(e);
        }
        close.run();
        if (finishedCleanly && guard.resolved() && failures.isEmpty()) {
            truncate.run();
        } else {
            try {
                recording.withhold("사례 정상 종료·정리 근거 없음 또는 pause 상태 불명 — §10.1 사후 수동 정리 대상"); // 메모리 보류가 기록보다 먼저다.
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            Exception first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    /** docker CLI 실행. 유한 대기, 0 이 아닌 종료는 예외. */
    static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(PauseGuard.CLI_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("docker 명령 대기 초과: " + command);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("docker 명령 실패(" + process.exitValue() + "): " + command + " → " + output);
        }
        return output;
    }

    static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("명령 대기 초과: " + Arrays.toString(command));
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 자동 입장 회차가 테스트의 수동 processAdmissions 와 경쟁하지 않게 이 컨텍스트에서만 대체한다(실제 회차는 CouponWaitingScheduler*Test). */
    @MockitoBean
    private CouponWaitingScheduler couponWaitingScheduler;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CouponFacade couponFacade;
    @Autowired
    private RecordingAdmissionRepository recording;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
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

    // ------------------------------------------------------------------ 대상 확인·관찰

    /** 대상 앱 endpoint·database·컨테이너를 대조한다. 하나라도 다르면 장애 조작 없이 시작 조건 실패다. */
    private void verifyTarget(String testCase) throws Exception {
        containerId = verifyTarget(testCase, redisProperties, master, applicationContext, recording.log());
        pauseGuard.target(containerId);
    }

    /** 캐시 장애 opt-in 과 공유하는 대상 대조. 확인된 컨테이너 ID 를 돌려주며 대상 기록을 남긴다. */
    static String verifyTarget(String testCase, RedisProperties redisProperties, RedisTemplate<String, String> master,
                               ApplicationContext applicationContext, FaultLog log) throws Exception {
        PauseGuard.requireTimeoutBudget(redisProperties.commandTimeout());
        assertAll(
            () -> assertThat(applicationContext.getBeansOfType(RedisTestContainersConfig.class)).as("fixture Redis 구성 없음").isEmpty(),
            () -> assertThat(redisProperties.master().host()).isEqualTo(MASTER_HOST),
            () -> assertThat(redisProperties.master().port()).isEqualTo(MASTER_PORT),
            () -> assertThat(redisProperties.database()).isEqualTo(DATABASE),
            () -> assertThat(redisProperties.replicas()).hasSize(1),
            () -> assertThat(redisProperties.replicas().get(0).port()).isEqualTo(REPLICA_PORT)
        );
        String ids = docker("ps", "--filter", "publish=" + MASTER_PORT, "--format", "{{.ID}}");
        assertThat(ids.lines().toList()).as("master 포트를 공개한 컨테이너가 하나여야 한다").hasSize(1);
        String id = ids.lines().findFirst().orElseThrow();
        String inspect = docker("inspect", id, "--format",
            "{{.Id}}|{{.State.Status}}|{{.State.Paused}}|{{index .Config.Labels \"com.docker.compose.project\"}}|"
                + "{{index .Config.Labels \"com.docker.compose.service\"}}|{{(index (index .NetworkSettings.Ports \"6379/tcp\") 0).HostPort}}|{{.Config.Image}}");
        String[] parts = inspect.split("\\|");
        String containerRunId = runIdFromInfo(docker("exec", id, "redis-cli", "INFO", "server"));
        String connectedRunId = runId(master);
        assertAll(
            () -> assertThat(parts[1]).isEqualTo("running"),
            () -> assertThat(parts[2]).isEqualTo("false"),
            () -> assertThat(parts[4]).as("compose 서비스").isEqualTo("redis-master"),
            () -> assertThat(parts[5]).isEqualTo(String.valueOf(MASTER_PORT)),
            () -> assertThat(connectedRunId).as("앱 master 연결이 이 컨테이너 서버").isEqualTo(containerRunId)
        );
        String serverInfo = docker("exec", id, "redis-cli", "INFO", "server");
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("event", "target");
        target.put("case", testCase);
        target.put("container", parts[0]);
        target.put("project", parts[3]);
        target.put("service", parts[4]);
        target.put("image", parts[6]);
        target.put("endpoint", MASTER_HOST + ":" + MASTER_PORT);
        target.put("replica", REPLICA_HOST + ":" + REPLICA_PORT);
        target.put("database", DATABASE);
        target.put("runId", containerRunId);
        target.put("redisVersion", infoValue(serverInfo, "redis_version"));
        target.put("mysql", mysqlEvidence());
        target.put("redisCommandTimeout", String.valueOf(redisProperties.commandTimeout()));
        target.put("httpRead", PauseGuard.HTTP_READ.toString());
        log.append(target);
        return parts[0];
    }

    /** MySQL 테스트 컨테이너는 호스트 포트가 임의라 포트 바인딩 문자열로 찾는다. 찾지 못하면 불명으로 남긴다. */
    private static String mysqlEvidence() {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url", "불명");
        String port = url.replaceAll(".*:(\\d+)/.*", "$1");
        try {
            List<String> matches = docker("ps", "--format", "{{.ID}} {{.Image}} {{.Ports}}").lines()
                .filter(line -> line.contains(":" + port + "->")).toList();
            return url + " container=" + (matches.size() == 1 ? matches.get(0) : "불명" + matches);
        } catch (Exception e) {
            return url + " container=불명(" + e.getMessage() + ")";
        }
    }

    /**
     * 다른 프로세스·클라이언트가 같은 master 를 쓰지 않는지 확인한다. 호스트에서 master 포트로 맺은 연결의 소유 PID 가 이 JVM 뿐이어야 하고,
     * 서버의 NORMAL 클라이언트는 이 앱 연결과 같은 주소이거나 관찰용 redis-cli 여야 한다.
     */
    private void requireNoOtherClients(String testCase, long ownClientId) throws Exception {
        requireNoOtherClients(testCase, containerId, ownClientId, recording.log());
    }

    static void requireNoOtherClients(String testCase, String containerId, long ownClientId, FaultLog log) throws Exception {
        long pid = ProcessHandle.current().pid();
        List<String> foreign = new ArrayList<>();
        for (String line : run("lsof", "-nP", "-iTCP:" + MASTER_PORT, "-sTCP:ESTABLISHED").lines().skip(1).toList()) {
            String[] cols = line.trim().split("\\s+");
            String name = cols[cols.length - 2].equals("(ESTABLISHED)") ? cols[cols.length - 3] : cols[cols.length - 2];
            boolean clientSide = line.contains("->127.0.0.1:" + MASTER_PORT) || line.contains("->[::1]:" + MASTER_PORT);
            if (clientSide && !cols[1].equals(String.valueOf(pid))) {
                foreign.add(line.trim() + " [" + name + "]");
            }
        }
        String own = docker("exec", containerId, "redis-cli", "CLIENT", "LIST", "ID", String.valueOf(ownClientId));
        String ownIp = field(own, "addr").replaceAll(":\\d+$", "");
        String normal = docker("exec", containerId, "redis-cli", "CLIENT", "LIST", "TYPE", "NORMAL");
        for (String client : normal.lines().toList()) {
            String ip = field(client, "addr").replaceAll(":\\d+$", "");
            boolean observer = field(client, "cmd").equals("client|list");
            if (!ip.equals(ownIp) && !observer) {
                foreign.add("redis client " + client);
            }
        }
        log.append(Map.of("event", "interference-check", "case", testCase, "jvmPid", pid, "ownClient", own,
            "normalClients", normal, "foreign", foreign));
        assertThat(foreign).as("다른 프로세스·클라이언트가 있으면 pause 하지 않는다").isEmpty();
    }

    static String field(String clientLine, String name) {
        for (String token : clientLine.split(" ")) {
            if (token.startsWith(name + "=")) {
                return token.substring(name.length() + 1);
            }
        }
        return "";
    }

    private static String infoValue(String info, String name) {
        return info.lines().filter(line -> line.startsWith(name + ":")).map(line -> line.substring(name.length() + 1).trim())
            .findFirst().orElse("불명");
    }

    private static String runIdFromInfo(String info) {
        return infoValue(info, "run_id");
    }

    /** 앱의 master 공유 연결(비파이프라인·비트랜잭션)에서 CLIENT ID 를 유한 대기로 받는다. */
    private long clientId() {
        return clientId(master);
    }

    private String runId() {
        return runId(master);
    }

    @SuppressWarnings("unchecked")
    static long clientId(RedisTemplate<String, String> master) {
        return master.execute((RedisCallback<Long>) connection -> {
            try {
                return ((RedisClusterAsyncCommands<byte[], byte[]>) connection.getNativeConnection()).clientId()
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("CLIENT ID 응답 없음", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    static String runId(RedisTemplate<String, String> master) {
        return master.execute((RedisCallback<String>) connection -> {
            try {
                return runIdFromInfo(((RedisClusterAsyncCommands<byte[], byte[]>) connection.getNativeConnection()).info("server")
                    .get(WAIT_SECONDS, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new IllegalStateException("INFO server 응답 없음", e);
            }
        });
    }

    /** 앱 연결의 마지막 명령을 바꾸지 않는 별도 관찰 연결(컨테이너 안 redis-cli)로 PROCESSING 점유 Set 을 본다. */
    private Set<String> observedMembers() throws Exception {
        return observedMembers(containerId);
    }

    static Set<String> observedMembers(String containerId) throws Exception {
        String out = docker("exec", containerId, "redis-cli", "-n", String.valueOf(DATABASE), "SMEMBERS", "waiting:processing:v1:" + OPERATING);
        return out.isBlank() ? Set.of() : Set.copyOf(out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    /** 같은 별도 관찰 연결로 이 운영 캠페인 대기실 키 수를 본다. */
    private long observedWaitingKeys() throws Exception {
        return observedKeys(containerId, CouponAdmissionRedisRepository.waitingKeys(OPERATING));
    }

    /** 별도 관찰 연결로 주어진 키 중 존재하는 수를 본다. */
    static long observedKeys(String containerId, List<String> keys) throws Exception {
        List<String> command = new ArrayList<>(List.of("exec", containerId, "redis-cli", "-n", String.valueOf(DATABASE), "EXISTS"));
        command.addAll(keys);
        return Long.parseLong(docker(command.toArray(String[]::new)).trim());
    }

    /** DB 결과·같은 연결 경과를 확인한 뒤 이 사례 운영 캠페인의 대기 상태를 무효화한다(§12.5 원칙). 제품 API 가 아닌 test 단계다. */
    private void invalidateWaiting(String testCase) throws Exception {
        recording.cleanupStep(testCase);
        master.delete(CouponAdmissionRedisRepository.waitingKeys(OPERATING));
        recording.log().append(Map.of("event", "waiting-invalidated", "case", testCase, "keysLeft", observedWaitingKeys()));
    }

    private void pause(String testCase) throws Exception {
        pauseGuard.pause(testCase);
    }

    /** HTTP 관찰 끝의 복구. 진입 문을 닫고 진행 중 pause 의 종료를 확인한 뒤 실제 상태로 복구한다. */
    private void unpause(String testCase) throws Exception {
        pauseGuard.closeEntryAndRestore(testCase);
    }

    /** 서버 요청이 끝난 뒤의 최종 복구. 시도 이력이 있으면 실제 상태를 다시 조회하고, 불명이 남았으면 정리·재개 전에 멈춘다. */
    private void finalRestore(String testCase) throws Exception {
        pauseGuard.restore(testCase + " final");
        pauseGuard.requireResolved();
    }

    // ------------------------------------------------------------------ 공통 준비·정리

    /** 닫기 → 닫힘 확인 → 운영 캠페인 대기실 키 0 확인 → 데이터 준비 commit → 명시 열기. 기존 키가 있으면 지우지 않고 시작 조건 실패다. */
    private List<Long> prepare(String testCase, int users) throws Exception {
        recording.requireNotWithheld(); // 앞 사례가 근거 없이 끝났으면 준비·DB 변경·열기·정리 전에 멈춘다.
        assertThat(couponFacade.close()).isFalse();
        assertThat(couponFacade.status()).isFalse();
        verifyTarget(testCase);
        long existing = observedWaitingKeys();
        recording.log().append(Map.of("event", "start-keys", "case", testCase, "keys", existing));
        assertThat(existing).as("시작 전 운영 캠페인 대기실 키가 없어야 한다").isZero();
        List<Long> ids = new ArrayList<>(fixture.users(users));
        ids.add(fixture.operatingCampaign(OPERATING, 10));
        assertThat(couponFacade.open()).isTrue();
        return ids;
    }

    @BeforeEach
    void reset() {
        finishedCleanly = false;
        pauseGuard = new PauseGuard(CouponLocalRedisFaultIntegrationTest::docker, recording.log());
        limitHttpRead(rest);
    }

    /** test HTTP 클라이언트의 연결 5초·read 한도. 시간 초과는 관찰 실패이지 서버 요청 종료가 아니다. */
    static void limitHttpRead(TestRestTemplate rest) {
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        http.setReadTimeout((int) PauseGuard.HTTP_READ.toMillis());
        rest.getRestTemplate().setRequestFactory(http);
    }

    @AfterEach
    void restoreRedis() throws Exception {
        endCase(pauseGuard, recording, finishedCleanly, couponFacade::close, databaseCleanUp::truncateAllTables);
    }

    static HttpEntity<Object> as(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(String testCase, Long userId, Long campaignId, String waitingId) {
        recording.next(testCase, userId, campaignId);
        HttpEntity<Object> request = as(userId);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        if (waitingId != null) {
            headers.set(CouponV1Controller.WAITING_ID_HEADER, waitingId);
        }
        return rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers), COUPON);
    }

    /** 등록 → 입장 처리(자동 주기 없음) 뒤 waitingId. 장애 전 준비 단계다. */
    private String admit(String testCase, Long userId, Long campaignId) {
        recording.next(testCase, userId, campaignId);
        var registered = rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/waiting", HttpMethod.POST, as(userId), WAITING);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(couponFacade.processAdmissions()).isPresent();
        return registered.getBody().data().waitingId();
    }

    // ------------------------------------------------------------------ Q9

    @DisplayName("Q9: 실행 시작 중 Redis 가 멈추면 신규 요청은 503·DB 미진입·닫힘이고 기존 결과·목록은 유지된다. unpause 뒤 같은 연결의 CLIENT ID·run_id 가 같음을 확인하고 자기 대기실 키만 무효화하며 닫힘을 유지한다.")
    @Test
    void q9AcquireFailure() throws Exception {
        String testCase = "Q9";
        List<Long> ids = prepare(testCase, 2);
        Long owner = ids.get(0);
        Long newcomer = ids.get(1);
        Long campaignId = ids.get(2);

        long beforeId = clientId();
        String beforeRunId = runId();
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId));

        String ownerWaiting = admit(testCase, owner, campaignId);
        String newcomerWaiting = admit(testCase, newcomer, campaignId);
        var first = issue(testCase, owner, campaignId, ownerWaiting);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long ownerCoupon = first.getBody().data().couponId();
        String lastCommand = docker("exec", containerId, "redis-cli", "CLIENT", "LIST", "ID", String.valueOf(beforeId));
        recording.log().append(Map.of("event", "path-check", "case", testCase, "client", lastCommand));
        assertThat(field(lastCommand, "cmd")).as("정상 종료 기록(finish Lua)이 같은 master 연결의 마지막 명령").isIn("evalsha", "eval");
        assertThat(observedMembers()).isEmpty();

        requireNoOtherClients(testCase, beforeId);
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> rejected;
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> storedAgain;
        ResponseEntity<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> list;
        boolean closedDuringFault;
        long issuedDuringFault;
        long newcomerCoupons;
        pause(testCase);
        try {
            rejected = issue(testCase, newcomer, campaignId, newcomerWaiting);
            closedDuringFault = !couponFacade.status();
            issuedDuringFault = fixture.issuedCount(campaignId);
            newcomerCoupons = fixture.couponCount(campaignId, newcomer);
            storedAgain = issue(testCase, owner, campaignId, null);
            list = rest.exchange("/api/v1/coupons", HttpMethod.GET, as(owner), PAGE);
        } finally {
            unpause(testCase);
        }

        long afterId = clientId();
        String afterRunId = runId();
        String newcomerToken = recording.waitingIdOf(newcomer);
        Set<String> membersAfterUnpause = observedMembers();
        recording.log().append(Map.of("event", "after", "case", testCase, "clientId", afterId, "runId", afterRunId,
            "newcomerWaitingId", String.valueOf(newcomerToken), "processingAfterUnpause", membersAfterUnpause,
            "db", Map.of("issued", fixture.issuedCount(campaignId), "coupons", fixture.couponCount(campaignId))));
        assertAll(
            () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(rejected.getBody().meta().errorCode()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"),
            () -> assertThat(closedDuringFault).isTrue(),
            () -> assertThat(issuedDuringFault).as("DB 발급 미진입").isEqualTo(1),
            () -> assertThat(newcomerCoupons).isZero(),
            () -> assertThat(storedAgain.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(storedAgain.getBody().data().couponId()).isEqualTo(ownerCoupon),
            () -> assertThat(list.getBody().data().items()).extracting(CouponV1Dto.OwnedCouponResponse::couponId).containsExactly(ownerCoupon),
            () -> assertThat(afterId).as("장애 전후 같은 연결").isEqualTo(beforeId),
            () -> assertThat(afterRunId).as("장애 전후 같은 서버 프로세스").isEqualTo(beforeRunId),
            () -> assertThat(newcomerToken).isEqualTo(newcomerWaiting)
        );

        finalRestore(testCase);
        invalidateWaiting(testCase);
        long removed = observedWaitingKeys();
        Set<String> finalMembers = observedMembers();
        recording.log().append(Map.of("event", "cleanup", "case", testCase, "keysLeft", removed,
            "members", finalMembers, "accepting", couponFacade.status(),
            "db", Map.of("issued", fixture.issuedCount(campaignId), "coupons", fixture.couponCount(campaignId))));
        System.out.printf("[coupon-q9] container=%s runId=%s clientId=%d->%d rejected=%s stored=%s lateProcessing=%s keysLeft=%d finalProcessing=%s accepting=%s issued=%d%n",
            containerId, beforeRunId, beforeId, afterId, rejected.getStatusCode(), storedAgain.getStatusCode(), membersAfterUnpause,
            removed, finalMembers, couponFacade.status(), fixture.issuedCount(campaignId));
        assertAll(
            () -> assertThat(finalMembers).isEmpty(),
            () -> assertThat(couponFacade.status()).as("Q9 는 닫힘을 유지하며 끝난다").isFalse(),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1)
        );
        finishedCleanly = true;
    }

    // ------------------------------------------------------------------ Q10

    @DisplayName("Q10: commit 뒤 종료 기록 직전에 Redis 가 멈춰도 201·쿠폰·발급 수가 유지되고 닫힌다. unpause 뒤 종료·DB 결과와 같은 연결 경과를 확인하고 자기 대기실 키를 무효화한 뒤 명시적으로 열어 새 등록·발급을 수용하며, 장애 전 READY 였던 미발급자의 옛 권한은 404·효과 0 이고 기존 보유자는 200 이 우선한다.")
    @Test
    void q10ReleaseFailure() throws Exception {
        String testCase = "Q10";
        List<Long> ids = prepare(testCase, 3);
        Long issuer = ids.get(0);
        Long next = ids.get(1);
        Long waiter = ids.get(2);
        Long campaignId = ids.get(3);

        long beforeId = clientId();
        String beforeRunId = runId();
        recording.log().append(Map.of("event", "before", "case", testCase, "clientId", beforeId, "runId", beforeRunId));
        requireNoOtherClients(testCase, beforeId);

        String issuerWaiting = admit(testCase, issuer, campaignId);
        String waiterOldWaiting = admit(testCase, waiter, campaignId); // 장애 전 READY·미발급. 무효화 전에 옛 ID 를 남긴다.
        recording.log().append(Map.of("event", "waiter-old-id", "case", testCase, "userId", waiter, "waitingId", waiterOldWaiting));
        PauseGuard guard = pauseGuard;
        recording.beforeNextFinish(() -> guard.pauseInRequest(testCase));
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> created;
        try {
            created = issue(testCase, issuer, campaignId, issuerWaiting);
        } finally {
            unpause(testCase);
        }
        String issuerToken = recording.waitingIdOf(issuer);
        String releaseOutcome = recording.finishOutcomeOf(issuerToken);
        boolean closedAfterFault = !couponFacade.status();

        long afterId = clientId();
        String afterRunId = runId();
        Set<String> membersAfterUnpause = observedMembers();
        recording.log().append(Map.of("event", "after", "case", testCase, "clientId", afterId, "runId", afterRunId,
            "issuerWaitingId", String.valueOf(issuerToken), "finishOutcome", String.valueOf(releaseOutcome),
            "processingAfterUnpause", membersAfterUnpause,
            "db", Map.of("issued", fixture.issuedCount(campaignId), "coupons", fixture.couponCount(campaignId))));
        assertAll(
            () -> assertThat(created.getStatusCode()).as("확정된 발급은 201 유지").isEqualTo(HttpStatus.CREATED),
            () -> assertThat(fixture.couponCount(campaignId, issuer)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(releaseOutcome).as("실제 finish Lua 실패/timeout").startsWith("failed"),
            () -> assertThat(closedAfterFault).isTrue(),
            () -> assertThat(afterId).isEqualTo(beforeId),
            () -> assertThat(afterRunId).isEqualTo(beforeRunId)
        );

        // HTTP 응답으로 서버 요청 종료를 확인한 뒤 최종 복구를 다시 확인한다. DB 결과·같은 연결의 후속 응답도 확인했다.
        // 대기 상태를 무효화하고 새 대기실로 다시 연다(§12.5 원칙).
        finalRestore(testCase);
        invalidateWaiting(testCase);
        long removed = observedWaitingKeys();
        Set<String> afterCleanup = observedMembers();
        assertThat(afterCleanup).isEmpty();
        assertThat(couponFacade.open()).isTrue();
        var admitted = issue(testCase, next, campaignId, admit(testCase, next, campaignId));
        Set<String> finalMembers = observedMembers();
        recording.log().append(Map.of("event", "invalidate-and-reopen", "case", testCase, "waitingIds", List.of(issuerToken), "keysLeft", removed,
            "afterCleanup", afterCleanup, "newRequest", admitted.getStatusCode().value(), "finalMembers", finalMembers,
            "db", Map.of("issued", fixture.issuedCount(campaignId), "coupons", fixture.couponCount(campaignId))));
        System.out.printf("[coupon-q10] container=%s runId=%s clientId=%d->%d created=%s finish=%s lateProcessing=%s keysLeft=%d reopenedRequest=%s finalProcessing=%s issued=%d%n",
            containerId, beforeRunId, beforeId, afterId, created.getStatusCode(), releaseOutcome, membersAfterUnpause, removed,
            admitted.getStatusCode(), finalMembers, fixture.issuedCount(campaignId));
        assertAll(
            () -> assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(finalMembers).isEmpty(),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(2),
            () -> assertThat(couponFacade.status()).isTrue()
        );

        // 재개 뒤 옛 권한: 아직 새로 등록하지 않은 미발급자의 옛 헤더는 404·DB 효과 0 이다.
        var oldBeforeRegister = issue(testCase, waiter, campaignId, waiterOldWaiting);
        long issuedAfterOld = fixture.issuedCount(campaignId);
        long waiterCouponsAfterOld = fixture.couponCount(campaignId, waiter);
        // 같은 사용자가 다시 등록하면 새 ID 다. 옛 헤더는 여전히 404 이고 새 항목(상태·순번)을 바꾸지 않는다.
        recording.next(testCase, waiter, campaignId);
        var reRegistered = rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/waiting", HttpMethod.POST, as(waiter), WAITING);
        String waiterNewWaiting = reRegistered.getBody().data().waitingId();
        var entryBeforeOld = rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/waiting", HttpMethod.GET, as(waiter), WAITING);
        var oldAfterRegister = issue(testCase, waiter, campaignId, waiterOldWaiting);
        var entryAfterOld = rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/waiting", HttpMethod.GET, as(waiter), WAITING);
        Set<String> membersAfterOld = observedMembers();
        // 새 권한으로는 정상 발급된다.
        assertThat(couponFacade.processAdmissions()).isPresent();
        var issuedWithNew = issue(testCase, waiter, campaignId, waiterNewWaiting);
        // 이미 발급된 사용자는 옛 헤더·헤더 없음에도 기존 결과가 우선이다.
        var holderOldHeader = issue(testCase, issuer, campaignId, issuerWaiting);
        var holderNoHeader = issue(testCase, issuer, campaignId, null);
        Map<String, Object> oldPermission = new LinkedHashMap<>();
        oldPermission.put("event", "old-permission-after-reopen");
        oldPermission.put("case", testCase);
        oldPermission.put("waiter", waiter);
        oldPermission.put("oldWaitingId", waiterOldWaiting);
        oldPermission.put("newWaitingId", String.valueOf(waiterNewWaiting));
        oldPermission.put("oldBeforeRegister", oldBeforeRegister.getStatusCode().value());
        oldPermission.put("oldAfterRegister", oldAfterRegister.getStatusCode().value());
        oldPermission.put("issuedWithNew", issuedWithNew.getStatusCode().value());
        oldPermission.put("holderOldHeader", holderOldHeader.getStatusCode().value());
        oldPermission.put("holderNoHeader", holderNoHeader.getStatusCode().value());
        oldPermission.put("db", Map.of("issued", fixture.issuedCount(campaignId), "coupons", fixture.couponCount(campaignId)));
        recording.log().append(oldPermission);
        System.out.printf("[coupon-q10-old] old=%s reRegistered=%s oldAfterRegister=%s newIssue=%s holderOld=%s holderNone=%s issued=%d%n",
            oldBeforeRegister.getStatusCode(), reRegistered.getStatusCode(), oldAfterRegister.getStatusCode(),
            issuedWithNew.getStatusCode(), holderOldHeader.getStatusCode(), holderNoHeader.getStatusCode(), fixture.issuedCount(campaignId));
        Long issuerCoupon = created.getBody().data().couponId();
        assertAll(
            () -> assertThat(oldBeforeRegister.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(oldBeforeRegister.getBody().meta().errorCode()).isEqualTo("COUPON_WAITING_NOT_FOUND"),
            () -> assertThat(issuedAfterOld).as("옛 권한 DB 효과 0").isEqualTo(2),
            () -> assertThat(waiterCouponsAfterOld).isZero(),
            () -> assertThat(reRegistered.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(waiterNewWaiting).isNotEqualTo(waiterOldWaiting),
            () -> assertThat(oldAfterRegister.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(oldAfterRegister.getBody().meta().errorCode()).isEqualTo("COUPON_WAITING_NOT_FOUND"),
            () -> assertThat(entryAfterOld.getBody().data()).as("옛 헤더가 새 항목을 바꾸지 않음").isEqualTo(entryBeforeOld.getBody().data()),
            () -> assertThat(membersAfterOld).isEmpty(),
            () -> assertThat(issuedWithNew.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(fixture.couponCount(campaignId, waiter)).isEqualTo(1),
            () -> assertThat(holderOldHeader.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(holderOldHeader.getBody().data().couponId()).isEqualTo(issuerCoupon),
            () -> assertThat(holderNoHeader.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(holderNoHeader.getBody().data().couponId()).isEqualTo(issuerCoupon),
            () -> assertThat(fixture.couponCount(campaignId, issuer)).isEqualTo(1),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(3)
        );

        // 마지막 정리: 닫기 → 모든 HTTP 응답 수신(자동 회차 없음)·같은 연결 경과 → 최종 복구 → 자기 대기실 키 무효화 → 키 0, DB 결과 보존.
        couponFacade.close();
        assertThat(couponFacade.status()).isFalse();
        long endClientId = clientId();
        String endRunId = runId();
        long issuedBeforeCleanup = fixture.issuedCount(campaignId);
        long couponsBeforeCleanup = fixture.couponCount(campaignId);
        recording.log().append(Map.of("event", "final-cleanup-before", "case", testCase, "clientId", endClientId, "runId", endRunId,
            "keys", observedWaitingKeys(), "db", Map.of("issued", issuedBeforeCleanup, "coupons", couponsBeforeCleanup)));
        finalRestore(testCase + " end");
        assertThat(endClientId).as("종료 시점까지 같은 연결").isEqualTo(beforeId);
        assertThat(endRunId).as("종료 시점까지 같은 서버 프로세스").isEqualTo(beforeRunId);
        invalidateWaiting(testCase + " end");
        long keysAfterCleanup = observedWaitingKeys();
        Set<String> processingAfterCleanup = observedMembers();
        recording.log().append(Map.of("event", "final-cleanup-after", "case", testCase, "keys", keysAfterCleanup,
            "processing", processingAfterCleanup, "accepting", couponFacade.status(),
            "db", Map.of("issued", fixture.issuedCount(campaignId), "coupons", fixture.couponCount(campaignId))));
        System.out.printf("[coupon-q10-end] keysLeft=%d processing=%s issued=%d coupons=%d clientId=%d runId=%s%n", keysAfterCleanup,
            processingAfterCleanup, fixture.issuedCount(campaignId), fixture.couponCount(campaignId), endClientId, endRunId);
        assertAll(
            () -> assertThat(keysAfterCleanup).as("자기 대기실 키 5개 모두 없음").isZero(),
            () -> assertThat(processingAfterCleanup).isEmpty(),
            () -> assertThat(couponFacade.status()).isFalse(),
            () -> assertThat(issuedBeforeCleanup).isEqualTo(3),
            () -> assertThat(fixture.issuedCount(campaignId)).as("키 정리가 발급 수를 바꾸지 않음").isEqualTo(issuedBeforeCleanup),
            () -> assertThat(fixture.couponCount(campaignId)).as("키 정리가 쿠폰을 바꾸지 않음").isEqualTo(couponsBeforeCleanup)
        );
        finishedCleanly = true;
    }
}
