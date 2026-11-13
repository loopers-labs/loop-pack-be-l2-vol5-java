package com.loopers.interfaces.api;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.coupon.LocalLimiter;
import com.loopers.domain.coupon.CouponCampaignService;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisSemaphoreGate;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.support.coupon.BarrierIssueConfig;
import com.loopers.support.coupon.BarrierIssueService;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 이 테스트만 쓰는 Redis 컨테이너를 실제로 멈춰 신규 발급이 계속되는지 확인한다(설계서 철회 반영: Redis가 내려가도 신규 발급은 계속된다).
 * 다른 테스트가 함께 쓰는 Redis는 건드리지 않고, 재시작해도 같은 주소로 다시 붙도록 호스트 포트를 고정한다. mock이나 thread-local 카운트를 쓰지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BarrierIssueConfig.class)
@TestPropertySource(properties = "coupon.admission.capacity=3")
class CouponRedisFailureTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
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
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BarrierIssueService barrier;

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    private LocalLimiter localLimiter;

    @Autowired
    private CouponCampaignService campaignService;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final DockerClient docker = DockerClientFactory.instance().client();

    @BeforeEach
    void setUp() {
        startRedisIfStopped();
        resumeIfDegraded();
        gate.circuitBreaker().reset();
    }

    /** 앞선 테스트의 장애로 직행 중이면, Redis가 살아 있는 지금 재개 조건에 따라 돌아오게 한다. */
    private void resumeIfDegraded() {
        if (gate.isDegraded()) {
            await().atMost(DEADLINE).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                gate.tryResume();
                assertThat(gate.isDegraded()).isFalse();
            });
        }
    }

    @AfterEach
    void tearDown() {
        barrier.reset();
        try {
            startRedisIfStopped();
        } finally {
            databaseCleanUp.truncateAllTables();
        }
    }

    private void startRedisIfStopped() {
        if (!Boolean.TRUE.equals(docker.inspectContainerCmd(OUTAGE_REDIS.getContainerId()).exec().getState().getRunning())) {
            docker.startContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        }
        // 다시 시작한 직후에는 연결이 복구될 때까지 오류가 날 수 있으므로 예외도 재시도한다
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(300)).ignoreExceptions().untilAsserted(() -> gate.activeCount());
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> issue(Long campaignId, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return testRestTemplate.exchange(
            "/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> myCoupons(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return testRestTemplate.exchange(
            "/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(null, headers), new ParameterizedTypeReference<>() {}
        );
    }

    private void stopRedis() {
        docker.stopContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
    }

    @DisplayName("Redis를 실제로 멈춰도 신규 발급은 계속되고 기존 본인 목록이 유지되며, 실패가 쌓이면 breaker가 열려 Redis 호출을 건너뛴다.")
    @Test
    void newIssuesContinueWhileRedisIsDown_andBreakerSkipsRedis() {
        // arrange — 정상 상태에서 한 명이 Redis 경로로 발급받는다
        Long campaignId = campaignService.create("장애 중 발급", 100, "혜택").getId();
        var beforeOutage = issue(campaignId, 1L);
        assertThat(beforeOutage.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(gate.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // act 1 — Redis를 멈추고 새 사용자들이 요청한다
        stopRedis();
        for (long userId = 2; userId <= 8; userId++) {
            assertThat(issue(campaignId, userId).getStatusCode()).as("user %d", userId).isEqualTo(HttpStatus.CREATED);
        }

        // assert 1 — 신규 발급은 계속됐고, breaker가 열렸으며, 기존 목록과 재요청 결과가 유지된다
        assertThat(gate.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(couponJpaRepository.count()).isEqualTo(8);
        assertThat(campaignJpaRepository.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(8);
        assertThat(myCoupons(1L).getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId)
            .containsExactly(beforeOutage.getBody().data().couponId());
        var again = issue(campaignId, 1L);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().data().couponId()).isEqualTo(beforeOutage.getBody().data().couponId());

        // act 2 — breaker가 열린 뒤의 요청은 Redis를 호출하지 않는다(응답 못 받은 token이 더 늘지 않는다)
        int recordedWhenOpened = gate.unreleasedTokens().size();
        long startedAt = System.nanoTime();
        for (long userId = 9; userId <= 14; userId++) {
            assertThat(issue(campaignId, userId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert 2
        assertThat(gate.unreleasedTokens()).as("Redis를 건너뛰었으므로 새 token이 없다").hasSize(recordedWhenOpened);
        assertThat(elapsed).as("Redis timeout을 기다리지 않는다").isLessThan(Duration.ofSeconds(5));
        assertThat(couponJpaRepository.count()).isEqualTo(14);
        assertThat(localLimiter.inProgress()).as("직행 상한은 끝나면 반환").isZero();
    }

    @DisplayName("Redis가 멈춘 동안 직행 실행은 로컬 상한(C=3)에서 멈춘다: 세 실행을 붙잡고 네 번째가 오면 429 NO_PERMIT이고 DB 경로에 들어가지 않으며, 한 실행이 끝나면 네 번째가 발급받는다.")
    @Test
    void directPathIsBoundedByLocalLimit_whileRedisIsDown() throws Exception {
        // arrange — Redis를 멈추고 breaker를 열어 두고(실패 기록), 캠페인 한도는 충분히 크게 둔다
        Long campaignId = campaignService.create("직행 상한", 100, "혜택").getId();
        stopRedis();
        for (long userId = 100; userId < 106; userId++) {
            assertThat(issue(campaignId, userId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(gate.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        barrier.entered.clear();

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (long userId = 1; userId <= 3; userId++) {
                barrier.holds.put(userId, new CompletableFuture<>());
            }

            // act 1 — 직행 세 실행을 DB 진입 직전에 붙잡는다
            var inFlight = new java.util.HashMap<Long, CompletableFuture<ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>>>>();
            for (long userId = 1; userId <= 3; userId++) {
                long id = userId;
                inFlight.put(id, CompletableFuture.supplyAsync(() -> issue(campaignId, id), executor));
            }
            await().atMost(DEADLINE).untilAsserted(() -> assertThat(barrier.entered).containsExactlyInAnyOrder(1L, 2L, 3L));

            // assert 1 — 직행 진행 중 3, 네 번째는 429 NO_PERMIT, DB 경로 진입 없음
            assertThat(localLimiter.inProgress()).isEqualTo(3);
            var rejected = issue(campaignId, 4L);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(rejected.getBody().meta().errorCode()).isEqualTo("NO_PERMIT");
            assertThat(barrier.entered).doesNotContain(4L);

            // act 2 — 한 실행이 끝나 로컬 자리가 나면 네 번째 재요청이 발급받는다
            barrier.holds.get(1L).complete(null);
            assertThat(inFlight.get(1L).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            await().atMost(DEADLINE).untilAsserted(() -> assertThat(localLimiter.inProgress()).isEqualTo(2));
            assertThat(issue(campaignId, 4L).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // act 3 — 나머지도 끝난다
            barrier.holds.get(2L).complete(null);
            barrier.holds.get(3L).complete(null);
            assertThat(inFlight.get(2L).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(inFlight.get(3L).get(DEADLINE.toSeconds(), TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            await().atMost(DEADLINE).untilAsserted(() -> assertThat(localLimiter.inProgress()).isZero());
        } finally {
            barrier.reset();
            executor.shutdownNow();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
