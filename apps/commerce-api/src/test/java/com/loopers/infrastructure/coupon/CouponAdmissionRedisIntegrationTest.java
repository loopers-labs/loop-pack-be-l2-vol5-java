package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.RedisTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 테스트 fixture 가 띄운 Redis 컨테이너에 master template 으로 수용 Lua·SREM 을 실행한다(설계 §7, Q7·Q8·Q12 의 수용 계층 부분).
 * HTTP·DB 발급 TX 와 연결한 Q7 전체 검증이 아니다. 로컬 Redis pause 장애(Q9·Q10)도 다루지 않는다.
 *
 * <p>fixture 의 System property 덮어쓰기는 RedisProperties 바인딩보다 늦게 실행돼 일반 컨텍스트가 로컬 6379 에 붙을 수 있다.
 * 그래서 이 클래스는 fixture 컨테이너 주소를 {@link DynamicPropertySource} 로 먼저 넣고, 매 테스트의 첫 Redis 명령 전에
 * 바인딩 값·연결 팩토리 설정·실제 서버 run_id 를 컨테이너 직접 관찰과 대조한다. 다르면 Redis 에 쓰지 않고 준비 실패로 끝낸다.
 *
 * <p>컨테이너는 같은 JVM 의 다른 테스트와 공유한다. 시작 시 전용 키가 비어 있지 않으면 지우지 않고 실패하며,
 * 끝나면 이 테스트가 만든 token 만 SREM 한다.
 */
@SpringBootTest
// fixture Redis 주소 주입 등으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다(누적 시 Too many connections).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("수용 Lua 는 fixture Redis 컨테이너에서 C 를 넘기지 않고, SREM 은 자기 token 만 제거하며, 점유는 시간 경과로 사라지지 않는다.")
class CouponAdmissionRedisIntegrationTest {

    private static final String KEY = CouponAdmissionRedisRepository.PERMITS_KEY;
    private static final int C = 3;
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);

    @Autowired
    private CouponAdmissionRedisRepository repository;
    @Autowired
    private CouponAdmissionService service;
    @Autowired
    private CouponAdmissionProperties properties;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;

    private final List<String> ownTokens = new ArrayList<>();
    private final WorkerTermination workers = new WorkerTermination();
    private boolean targetVerified;

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
        // 3단위부터 open 은 운영 캠페인 설정을 요구한다. 이 클래스는 기본 token 경로만 보며 대기실 키는 쓰지 않는다.
        registry.add("commerce.coupon.waiting.campaign-id", () -> "1");
    }

    @BeforeEach
    void requireFixtureContainerAndEmptyKey() {
        service.close();
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
        assertThat(master.type(KEY))
            .as("시작 전 전용 키가 비어 있어야 한다. 남은 값은 다른 실행의 점유일 수 있어 지우지 않는다.")
            .isEqualTo(DataType.NONE);
    }

    @AfterEach
    void releaseOwnTokens() {
        service.close();
        if (!targetVerified) {
            return; // 대상 확인 전이면 어떤 Redis 에도 명령을 보내지 않는다.
        }
        if (!workers.safeToClean()) {
            throw new IllegalStateException("worker 종료를 확인하지 못해 token 정리를 보류한다. [tokens = " + ownTokens + "]");
        }
        if (!ownTokens.isEmpty()) {
            master.opsForSet().remove(KEY, ownTokens.toArray());
        }
        assertThat(master.type(KEY)).as("자기 token 정리 뒤 전용 키가 비어야 한다").isEqualTo(DataType.NONE);
    }

    private String newToken() {
        String token = UUID.randomUUID().toString();
        ownTokens.add(token);
        return token;
    }

    private Set<String> members() {
        return master.opsForSet().members(KEY);
    }

    private static ErrorType errorTypeOf(Throwable thrown) {
        return ((CoreException) thrown).getErrorType();
    }

    @DisplayName("주입된 template 은 fixture 컨테이너의 master 서버에 연결되고, 앱 설정의 C 는 3 이다.")
    @Test
    void usesMasterAndConfiguredCapacity() {
        Properties replication = master.execute((RedisCallback<Properties>) c -> c.serverCommands().info("replication"));
        Properties server = master.execute((RedisCallback<Properties>) c -> c.serverCommands().info("server"));

        System.out.printf("[coupon-admission] master=%s:%d database=%d container=%s containerRunId=%s connectedRunId=%s"
                + " redis_version=%s role=%s key=%s C=%d%n",
            redisProperties.master().host(), redisProperties.master().port(), redisProperties.database(),
            RedisTestContainersConfig.containerId(), RedisTestContainersConfig.serverRunId(), server.getProperty("run_id"),
            server.getProperty("redis_version"), replication.getProperty("role"), KEY, properties.capacity());
        assertAll(
            () -> assertThat(server.getProperty("run_id")).isEqualTo(RedisTestContainersConfig.serverRunId()),
            () -> assertThat(replication.getProperty("role")).isEqualTo("master"),
            () -> assertThat(properties.capacity()).isEqualTo(C)
        );
    }

    @DisplayName("획득은 서버에 적재된 수용 Lua 로 실행되고, 키에 TTL 이 없다.")
    @Test
    void acquireRunsLuaOnServer() throws Exception {
        String token = newToken();
        // Redis 는 스크립트 본문 바이트의 SHA1 로 캐시한다. 파일에서 직접 계산해 제품 코드에 조회 hook 을 두지 않는다.
        String scriptSha = sha1Hex(new ClassPathResource("redis/coupon-admission-acquire.lua").getContentAsByteArray());

        assertThat(repository.tryAcquire(token, C)).isEqualTo(Acquisition.ACQUIRED);

        List<Boolean> exists = master.execute((RedisCallback<List<Boolean>>) c -> c.scriptingCommands().scriptExists(scriptSha));
        assertAll(
            () -> assertThat(exists).containsExactly(true),
            () -> assertThat(members()).containsExactly(token),
            () -> assertThat(master.getExpire(KEY)).as("TTL 없음(-1)").isEqualTo(-1L)
        );
    }

    @DisplayName("C=3 에서 서로 다른 token 셋을 유지하면 넷째는 FULL, 하나를 반환하면 넷째가 획득한다.")
    @Test
    void fullAtCapacityThenReacquireAfterRelease() {
        String a = newToken();
        String b = newToken();
        String c = newToken();
        String d = newToken();

        assertAll(
            () -> assertThat(repository.tryAcquire(a, C)).isEqualTo(Acquisition.ACQUIRED),
            () -> assertThat(repository.tryAcquire(b, C)).isEqualTo(Acquisition.ACQUIRED),
            () -> assertThat(repository.tryAcquire(c, C)).isEqualTo(Acquisition.ACQUIRED)
        );
        assertThat(repository.tryAcquire(d, C)).isEqualTo(Acquisition.FULL);
        assertThat(members()).containsExactlyInAnyOrder(a, b, c);

        assertThat(repository.release(b)).isEqualTo(1L);
        assertThat(repository.tryAcquire(d, C)).isEqualTo(Acquisition.ACQUIRED);
        assertThat(members()).containsExactlyInAnyOrder(a, c, d);
    }

    @DisplayName("같은 token 재획득은 점유를 늘리지 않고, 가득 찬 상태에서도 자기 token 은 ACQUIRED 다.")
    @Test
    void sameTokenIsIdempotent() {
        String a = newToken();
        assertThat(repository.tryAcquire(a, C)).isEqualTo(Acquisition.ACQUIRED);
        assertThat(repository.tryAcquire(a, C)).isEqualTo(Acquisition.ACQUIRED);
        assertThat(master.opsForSet().size(KEY)).isEqualTo(1L);

        String b = newToken();
        String c = newToken();
        repository.tryAcquire(b, C);
        repository.tryAcquire(c, C);

        assertThat(repository.tryAcquire(a, C)).isEqualTo(Acquisition.ACQUIRED);
        assertThat(master.opsForSet().size(KEY)).isEqualTo(3L);
    }

    @DisplayName("자기 token 반복 반환·없는 token 반환은 0 이고 다른 활성 token 을 지우지 않는다.")
    @Test
    void releaseRemovesOnlyOwnToken() {
        String a = newToken();
        String b = newToken();
        String c = newToken();
        repository.tryAcquire(a, C);
        repository.tryAcquire(b, C);
        repository.tryAcquire(c, C);

        assertAll(
            () -> assertThat(repository.release(a)).isEqualTo(1L),
            () -> assertThat(repository.release(a)).isZero(),
            () -> assertThat(repository.release(UUID.randomUUID().toString())).isZero()
        );
        assertThat(members()).containsExactlyInAnyOrder(b, c);
    }

    @DisplayName("동시에 16개 token 이 경쟁해도 매 회차 정확히 C 개만 획득한다(5회).")
    @Test
    void concurrentAcquireNeverExceedsCapacity() throws Exception {
        int contenders = 16;
        for (int round = 0; round < 5; round++) {
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                tokens.add(newToken());
            }
            CountDownLatch ready = new CountDownLatch(contenders);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = workers.start(contenders);
            List<Future<Acquisition>> futures = new ArrayList<>();
            try {
                for (String token : tokens) {
                    futures.add(executor.submit((Callable<Acquisition>) () -> {
                        ready.countDown();
                        if (!start.await(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("시작 신호 대기 초과");
                        }
                        return repository.tryAcquire(token, C);
                    }));
                }
                assertThat(ready.await(RESULT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            } finally {
                start.countDown();
                workers.close(executor, TERMINATION_DEADLINE);
            }

            List<String> acquired = new ArrayList<>();
            int full = 0;
            for (int i = 0; i < contenders; i++) {
                Acquisition result = futures.get(i).get(0, TimeUnit.MILLISECONDS);
                if (result == Acquisition.ACQUIRED) {
                    acquired.add(tokens.get(i));
                } else {
                    full++;
                }
            }
            assertThat(acquired).hasSize(C);
            assertThat(full).isEqualTo(contenders - C);
            assertThat(members()).containsExactlyInAnyOrderElementsOf(acquired);

            for (String token : acquired) {
                assertThat(repository.release(token)).isEqualTo(1L);
            }
            assertThat(master.type(KEY)).isEqualTo(DataType.NONE);
        }
    }

    @DisplayName("TTL 이 없어 시간이 지나도 활성 점유가 회수되지 않고 새 token 은 계속 FULL 이다.")
    @Test
    void occupationSurvivesElapsedTime() throws Exception {
        String a = newToken();
        String b = newToken();
        String c = newToken();
        repository.tryAcquire(a, C);
        repository.tryAcquire(b, C);
        repository.tryAcquire(c, C);

        Thread.sleep(2_000);

        assertAll(
            () -> assertThat(master.getExpire(KEY)).isEqualTo(-1L),
            () -> assertThat(members()).containsExactlyInAnyOrder(a, b, c),
            () -> assertThat(repository.tryAcquire(newToken(), C)).isEqualTo(Acquisition.FULL)
        );
    }

    @DisplayName("빈 token·C<1 은 Lua 가 쓰기 전에 오류로 거절해 Set 을 바꾸지 않는다.")
    @Test
    void luaRejectsInvalidArgumentsBeforeWrite() {
        String token = newToken();

        assertAll(
            () -> assertThatThrownBy(() -> repository.tryAcquire("", C)).isInstanceOf(RuntimeException.class),
            () -> assertThatThrownBy(() -> repository.tryAcquire(token, 0)).isInstanceOf(RuntimeException.class),
            () -> assertThatThrownBy(() -> repository.tryAcquire(token, -1)).isInstanceOf(RuntimeException.class)
        );
        assertThat(master.type(KEY)).isEqualTo(DataType.NONE);
    }

    @DisplayName("서비스: 열린 상태에서 C=3 을 지나면 429·열림 유지, 반환 뒤 재획득, 모두 반환하면 키가 빈다.")
    @Test
    void serviceFullThenRelease() {
        service.open();
        String a = newToken();
        String b = newToken();
        String c = newToken();
        String d = newToken();
        service.acquire(a);
        service.acquire(b);
        service.acquire(c);

        assertThatThrownBy(() -> service.acquire(d))
            .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_FULL));
        assertThat(service.status()).isTrue();

        service.release(a);
        service.acquire(d);
        service.release(b);
        service.release(c);
        service.release(d);

        assertAll(
            () -> assertThat(service.status()).as("정상 첫 반환 1 은 닫지 않는다").isTrue(),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE)
        );
    }

    @DisplayName("서비스: 정상 실행의 첫 반환에서 token 이 이미 없으면(0) 닫히고, 남은 token 은 그대로다.")
    @Test
    void serviceClosesWhenFirstReleaseFindsNothing() {
        service.open();
        String a = newToken();
        String b = newToken();
        service.acquire(a);
        service.acquire(b);
        master.opsForSet().remove(KEY, a); // 외부 제거·상태 유실을 흉내 낸다. 이 테스트가 만든 token 이다.

        service.release(a);

        assertAll(
            () -> assertThat(service.status()).isFalse(),
            () -> assertThat(members()).containsExactly(b)
        );
    }

    @DisplayName("서비스: 닫힌 상태의 cleanup 은 지정 token 만 지우고, 반복·없는 token 은 0 이며 다시 열지 않는다.")
    @Test
    void serviceCleanupRemovesOnlyGivenTokens() {
        service.open();
        String a = newToken();
        String b = newToken();
        service.acquire(a);
        service.acquire(b);
        service.close();

        assertAll(
            () -> assertThat(service.cleanup(List.of(a, UUID.randomUUID().toString()))).isEqualTo(1L),
            () -> assertThat(service.cleanup(List.of(a))).isZero()
        );
        assertAll(
            () -> assertThat(members()).containsExactly(b),
            () -> assertThat(service.status()).isFalse()
        );
    }

    @DisplayName("서비스: 열린 상태의 cleanup 은 409 이고 Set 을 바꾸지 않는다.")
    @Test
    void serviceCleanupRejectedWhenOpen() {
        service.open();
        String a = newToken();
        service.acquire(a);

        assertThatThrownBy(() -> service.cleanup(List.of(a)))
            .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED));
        assertThat(members()).containsExactly(a);
    }

    @DisplayName("서비스: 키가 Set 이 아니면 실제 Redis 오류로 503·닫힘이고 키 값은 바뀌지 않는다.")
    @Test
    void wrongKeyTypeClosesWithoutWrite() {
        String marker = "coupon-admission-wrongtype-" + UUID.randomUUID();
        master.opsForValue().set(KEY, marker); // 시작 시 비어 있음을 확인한 전용 키에만 쓴다.
        try {
            service.open();

            assertThatThrownBy(() -> service.acquire(newToken()))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> assertThat(master.type(KEY)).isEqualTo(DataType.STRING),
                () -> assertThat(master.opsForValue().get(KEY)).isEqualTo(marker)
            );
        } finally {
            if (marker.equals(master.opsForValue().get(KEY))) {
                master.delete(KEY);
            }
        }
    }

    @DisplayName("서비스: 닫힌 상태의 acquire 는 실제 Redis 에 쓰지 않는다.")
    @Test
    void closedServiceDoesNotTouchRedis() {
        String a = newToken();

        assertThatThrownBy(() -> service.acquire(a))
            .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
        assertThat(master.type(KEY)).isEqualTo(DataType.NONE);
    }

    private static String sha1Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
