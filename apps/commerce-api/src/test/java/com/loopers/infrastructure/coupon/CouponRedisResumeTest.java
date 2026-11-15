package com.loopers.infrastructure.coupon;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.coupon.CouponAdmissionProperties;
import com.loopers.application.coupon.LocalLimiter;
import com.loopers.application.coupon.SemaphoreGate.Admission;
import com.loopers.application.coupon.SemaphoreGate.Status;
import com.loopers.config.redis.RedisConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 Redis 컨테이너를 멈췄다 올려 자동 재개의 안전 조건을 확인한다(설계서 8.2절). mock이나 thread-local 카운트를 쓰지 않는다.
 * 상태 유실은 복구 뒤 FLUSHALL로, "PONG은 되지만 쓰기가 막힌 Redis"는 maxmemory를 1바이트로 줄여(noeviction) 실제로 만든다.
 */
@SpringBootTest
@TestPropertySource(properties = "coupon.admission.capacity=3")
class CouponRedisResumeTest {

    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final Duration PAST_MIN_WAIT = Duration.ofSeconds(6);
    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> OUTAGE_REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest"))
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))
        ));

    static {
        OUTAGE_REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", OUTAGE_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", OUTAGE_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
    }

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> masterTemplate;

    @Autowired
    private LocalLimiter localLimiter;

    private final DockerClient docker = DockerClientFactory.instance().client();

    @BeforeEach
    void setUp() {
        startRedisIfStopped();
        setMaxMemory("0");
        if (gate.isDegraded()) {
            resumeEventually(gate);
        }
        gate.circuitBreaker().reset();
        flushAll();
        masterTemplate.opsForValue().set(RedisSemaphoreGate.EPOCH_KEY, "test-epoch");
    }

    @AfterEach
    void tearDown() {
        startRedisIfStopped();
        setMaxMemory("0");
    }

    @DisplayName("재개는 쓰기·읽기 왕복과 자기 token 정리, 용량 차감을 마친 뒤에만 한다: PONG만 되는 Redis에서는 돌아오지 않고, 상태가 유실됐으면 진행 중 실행을 용량에서 뺀다.")
    @Test
    void resumesOnlyAfterSafetyConditions_andDeductsLostInFlightExecutions() {
        // arrange — 정상 상태에서 두 실행이 자리를 얻고 계속 진행 중이다
        Admission inFlightA = gate.acquire();
        Admission inFlightB = gate.acquire();
        assertThat(List.of(inFlightA, inFlightB)).extracting(Admission::status).containsOnly(Status.ACQUIRED);

        // act 1 — Redis가 멈추고, 실패가 쌓여 DEGRADED가 된다
        stopRedis();
        List<Admission> duringOutage = acquireRepeatedly(gate, 6);

        // assert 1
        assertThat(duringOutage).extracting(Admission::status).contains(Status.UNCERTAIN, Status.SKIPPED);
        assertThat(gate.isDegraded()).isTrue();
        assertThat(gate.unreleasedTokens()).as("응답을 못 받은 자기 token을 기록").isNotEmpty();

        // act 2 — 최소 대기가 지나도 Redis가 아직 멈춰 있으면 돌아오지 않는다
        await().pollDelay(PAST_MIN_WAIT).atMost(DEADLINE).untilAsserted(() -> {
            gate.tryResume();
            assertThat(gate.isDegraded()).isTrue();
        });

        // act 3 — Redis가 돌아왔지만 상태가 유실됐고(FLUSHALL), 쓰기는 막혀 있다(PING은 응답한다)
        startRedis();
        flushAll();
        setMaxMemory("1");
        assertThat(ping()).as("PONG은 온다").isEqualTo("PONG");

        // assert 3 — PONG만 보고 재개하지 않는다
        await().pollDelay(PAST_MIN_WAIT).atMost(DEADLINE).untilAsserted(() -> {
            gate.tryResume();
            assertThat(gate.isDegraded()).as("쓰기·읽기 왕복이 실패하면 직행을 유지한다").isTrue();
        });

        // act 4 — 쓰기가 풀리면 안전 조건을 확인하고 돌아온다
        setMaxMemory("0");
        resumeEventually(gate);

        // assert 4 — 상태 유실을 감지해 sentinel을 다시 만들고, 응답 못 받은 token은 정리하고, 진행 중 두 실행은 용량에서 뺀다
        assertThat(masterTemplate.hasKey(RedisSemaphoreGate.EPOCH_KEY)).isTrue();
        assertThat(gate.unreleasedTokens()).isEmpty();
        assertThat(gate.uncountedTokens()).containsExactlyInAnyOrder(inFlightA.token(), inFlightB.token());
        assertThat(gate.activeCount()).isZero();

        // act 5·assert 5 — 유효 용량은 C(3) − 2 = 1이다. 진행 중인 두 실행을 포함해 동시에 3개를 넘지 않는다
        Admission first = gate.acquire();
        assertThat(first.status()).isEqualTo(Status.ACQUIRED);
        assertThat(gate.acquire().status()).as("진행 중 2 + 새 1 = C").isEqualTo(Status.FULL);

        // act 6·assert 6 — 진행 중이던 한 실행이 끝나면 그만큼 자리가 돌아온다
        gate.release(inFlightA);
        assertThat(gate.uncountedTokens()).containsExactly(inFlightB.token());
        Admission second = gate.acquire();
        assertThat(second.status()).isEqualTo(Status.ACQUIRED);
        assertThat(gate.acquire().status()).isEqualTo(Status.FULL);

        // 시험 호출을 모두 성공하면 breaker가 닫힌다
        assertThat(gate.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 정리 — 나머지 실행도 끝나면 활성 token과 차감이 모두 0이다
        gate.release(inFlightB);
        gate.release(first);
        gate.release(second);
        assertThat(gate.uncountedTokens()).isEmpty();
        assertThat(gate.activeCount()).isZero();
        assertThat(localLimiter.inProgress()).isZero();
    }

    @DisplayName("기동 때 Redis 확인에 실패해 직행으로 시작해도 Redis가 복구되면 자동으로 돌아오고, auto-resume이 꺼져 있으면 사람이 확인하기 전에는 조건이 충족돼도 돌아오지 않는다.")
    @Test
    void startupFailureResumesAutomatically_andManualModeWaitsForApproval() {
        // arrange — Redis가 멈춘 상태에서 기동 확인을 한 인스턴스(자동 재개)와, 실패가 쌓인 인스턴스(auto-resume 꺼짐)
        RedisSemaphoreGate startedWhileDown = newGate("startup-1", true);
        RedisSemaphoreGate manual = newGate("manual-1", false);
        stopRedis();

        // act 1
        startedWhileDown.onApplicationReady();
        acquireRepeatedly(manual, 6);

        // assert 1 — 둘 다 Redis를 건너뛰고 직행한다
        assertThat(startedWhileDown.isDegraded()).as("기동 확인 실패 → DEGRADED로 시작").isTrue();
        assertThat(startedWhileDown.acquire().status()).isEqualTo(Status.SKIPPED);
        assertThat(manual.isDegraded()).isTrue();

        // act 2 — Redis가 복구되고 최소 대기가 지난다
        startRedis();
        await().pollDelay(PAST_MIN_WAIT).atMost(DEADLINE).untilAsserted(() -> {
            startedWhileDown.tryResume();
            manual.tryResume();
            assertThat(startedWhileDown.isDegraded()).as("auto-resume: 조건을 채우면 자동으로 돌아온다").isFalse();
        });

        // assert 2 — 수동 모드는 조건이 모두 충족돼도 사람이 확인하기 전에는 그대로다
        assertThat(manual.isDegraded()).isTrue();
        assertThat(startedWhileDown.acquire().status()).isEqualTo(Status.ACQUIRED);

        // act 3 — 사람이 확인하면 다음 회차에 돌아온다
        manual.approveResume();
        manual.tryResume();

        // assert 3
        assertThat(manual.isDegraded()).isFalse();
        Admission afterApproval = manual.acquire();
        assertThat(afterApproval.status()).isEqualTo(Status.ACQUIRED);
        manual.release(afterApproval);
    }

    private RedisSemaphoreGate newGate(String instanceId, boolean autoResume) {
        return new RedisSemaphoreGate(masterTemplate, new CouponAdmissionProperties(3, autoResume, instanceId, null), localLimiter);
    }

    private List<Admission> acquireRepeatedly(RedisSemaphoreGate target, int times) {
        List<Admission> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            results.add(target.acquire());
        }
        return results;
    }

    private void resumeEventually(RedisSemaphoreGate target) {
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            target.tryResume();
            assertThat(target.isDegraded()).isFalse();
        });
    }

    private void stopRedis() {
        docker.stopContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
    }

    private void startRedis() {
        startRedisIfStopped();
    }

    private void startRedisIfStopped() {
        if (!Boolean.TRUE.equals(docker.inspectContainerCmd(OUTAGE_REDIS.getContainerId()).exec().getState().getRunning())) {
            docker.startContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        }
        // 다시 시작한 직후에는 연결이 복구될 때까지 오류가 날 수 있으므로 예외도 재시도한다
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(300)).ignoreExceptions().untilAsserted(() -> ping());
    }

    private String ping() {
        return masterTemplate.execute((RedisCallback<String>) connection -> connection.ping());
    }

    private void flushAll() {
        masterTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private void setMaxMemory(String bytes) {
        masterTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().setConfig("maxmemory", bytes);
            return null;
        });
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
