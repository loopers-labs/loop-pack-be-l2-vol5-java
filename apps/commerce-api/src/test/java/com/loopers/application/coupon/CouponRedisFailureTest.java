package com.loopers.application.coupon;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.infrastructure.coupon.AdmissionRedisConfig;
import com.loopers.infrastructure.coupon.RedisAdmissionPermits;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 수용 Redis가 실제로 멈췄다 다시 켜질 때(docs/week8/decisions.md INV-804, INV-805, D-2, D-5, D-6, D-7, D-10, D-13, D-14).
 *
 * <p>공용 테스트 Redis를 멈추면 다른 테스트가 깨지므로 이 클래스만 전용 컨테이너를 쓴다(D-13, ProductCacheRedisOutageTest와 같은 방식).
 * {@code @DynamicPropertySource}로 {@code datasource.redis.*}(master·replica)를 덮어쓰고, 수용 연결이 실제로 이 컨테이너에 붙었는지
 * 연결 주소와 컨테이너 안의 redis-cli로 확인한다. {@code docker pause}가 아니라 {@code docker stop}으로 멈추고, 다시 켜도 주소가 같도록
 * 고정 host 포트에 묶는다. AOF를 켜서(로컬 infra와 같음) 재시작 뒤에도 남은 token이 장부에 남는다.
 *
 * <p>테스트가 끝나면 Redis를 다시 살린다 — 전역 리스너(RedisCacheCleanUpListener)가 다음 테스트 전에 FLUSHALL을 보낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponRedisFailureTest {

    private static final Logger log = LoggerFactory.getLogger(CouponRedisFailureTest.class);
    private static final int REDIS_PORT = 6379;
    private static final int HOST_PORT = findFreePort();
    private static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(REDIS_PORT)
        .withCommand("redis-server", "--appendonly", "yes", "--save", "")
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
            .withPortBindings(new PortBinding(Ports.Binding.bindPort(HOST_PORT), new ExposedPort(REDIS_PORT))));

    // 끊긴 연결은 즉시 거절, 대답 없으면 500ms timeout. 상한은 DB 시간과 Windows Docker 편차를 감안한 여유값(Lettuce 기본 60초와 비교).
    private static final long FAIL_FAST_LIMIT_MILLIS = 2_000L;
    private static final Duration RECONNECT_LIMIT = Duration.ofSeconds(30);
    private static final long DEADLINE_SECONDS = 15;
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponListResponse>> COUPON_LIST =
        new ParameterizedTypeReference<>() {};

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", redis::getHost);
        registry.add("datasource.redis.master.port", () -> HOST_PORT);
        registry.add("datasource.redis.replicas[0].host", redis::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> HOST_PORT);
    }

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CouponAdmission couponAdmission;

    @Autowired
    private CouponCampaignSetup couponCampaignSetup;

    @MockitoSpyBean
    private CouponIssuer couponIssuer;

    @Autowired
    @Qualifier(AdmissionRedisConfig.ADMISSION_CONNECTION_FACTORY)
    private LettuceConnectionFactory admissionConnectionFactory;

    @Autowired
    @Qualifier(AdmissionRedisConfig.ADMISSION_REDIS_TEMPLATE)
    private RedisTemplate<String, String> admissionRedisTemplate;

    @Autowired
    private RedisTemplate<String, String> defaultRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ExecutorService executor;
    private CountDownLatch gate;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        couponAdmission.open();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (gate != null) {
            gate.countDown();
        }
        executor.shutdownNow();
        executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS);
        // Redis부터 살린다 — 정리(FLUSHALL)도 Redis가 응답해야 끝난다
        if (!Boolean.TRUE.equals(docker().inspectContainerCmd(redis.getContainerId()).exec().getState().getRunning())) {
            startRedis();
        }
        awaitReconnected(admissionRedisTemplate);
        awaitReconnected(defaultRedisTemplate);
        couponAdmission.open();
        databaseCleanUp.truncateAllTables();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DockerClient docker() {
        return redis.getDockerClient();
    }

    private static void stopRedis() {
        docker().stopContainerCmd(redis.getContainerId()).exec();
    }

    private static void startRedis() {
        docker().startContainerCmd(redis.getContainerId()).exec();
    }

    // Redis가 다시 대답(PONG)할 때까지 기다린다. 걸린 시간(ms)을 돌려준다.
    private static long awaitReconnected(RedisTemplate<String, String> template) {
        long start = System.nanoTime();
        long deadline = start + RECONNECT_LIMIT.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                String pong = template.execute((RedisCallback<String>) RedisConnection::ping);
                if ("PONG".equals(pong)) {
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Redis에 " + RECONNECT_LIMIT + " 안에 재연결하지 못했다", last);
    }

    private static HttpEntity<Void> asUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long campaignId, Long userId) {
        return testRestTemplate.exchange(
            "/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, asUser(userId), COUPON);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> myCoupons(Long userId) {
        return testRestTemplate.exchange("/api/v1/coupons", HttpMethod.GET, asUser(userId), COUPON_LIST);
    }

    private Long campaign() {
        return couponCampaignSetup.create("오픈 기념", 10, "5,000원 할인권", 5_000L).campaignId();
    }

    private Set<String> tokens() {
        Set<String> members = admissionRedisTemplate.opsForSet().members(RedisAdmissionPermits.PERMITS_KEY);
        return members == null ? Set.of() : new HashSet<>(members);
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

    private static String redisCli(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        Container.ExecResult result = redis.execInContainer(command);
        return result.getStdout().trim();
    }

    @DisplayName("수용 연결은 이 테스트 전용 컨테이너(고정 host 포트)의 master에 붙는다.")
    @Test
    void admissionConnectsToDedicatedContainer() throws Exception {
        String token = couponAdmission.acquire();

        assertAll(
            () -> assertThat(admissionConnectionFactory.getPort()).isEqualTo(HOST_PORT),
            () -> assertThat(admissionConnectionFactory.getHostName()).isEqualTo(redis.getHost()),
            () -> assertThat(redisCli("SMEMBERS", RedisAdmissionPermits.PERMITS_KEY)).isEqualTo(token)
        );
        couponAdmission.release(token);
    }

    @DisplayName("자리를 쥔 동안 token 키에 만료가 없다(TTL -1, D-5).")
    @Test
    void heldTokenHasNoExpiry() throws Exception {
        String token = couponAdmission.acquire();

        assertAll(
            () -> assertThat(admissionRedisTemplate.getExpire(RedisAdmissionPermits.PERMITS_KEY)).isEqualTo(-1L),
            () -> assertThat(redisCli("TTL", RedisAdmissionPermits.PERMITS_KEY)).isEqualTo("-1"),
            () -> assertThat(tokens()).containsExactly(token)
        );
        couponAdmission.release(token);
    }

    @DisplayName("Redis가 멈추면, 신규 발급은 곧바로 503이고 수용이 닫히며 DB는 그대로다. 이미 받은 사용자는 200, 보유 목록도 그대로 나온다.")
    @Test
    void stopped_newIssueUnavailable_existingStillServed() {
        Long campaignId = campaign();
        Long couponId = issue(campaignId, 1L).getBody().data().couponId();
        stopRedis();

        long start = System.nanoTime();
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> newcomer = issue(campaignId, 2L);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> existing = issue(campaignId, 1L);
        ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> mine = myCoupons(1L);

        assertAll(
            () -> assertThat(newcomer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(newcomer.getBody().data()).isNull(),
            () -> assertThat(millis).isLessThan(FAIL_FAST_LIMIT_MILLIS),
            () -> assertThat(couponAdmission.isOpen()).isFalse(),
            () -> assertThat(couponRows(campaignId)).isEqualTo(1L),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(existing.getBody().data().couponId()).isEqualTo(couponId),
            () -> assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(mine.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId)
                .containsExactly(couponId)
        );
        log.info("[D-13 중지] 신규 발급 503까지 {}ms", millis);
    }

    @DisplayName("Redis가 다시 켜져 PONG을 받아도 스스로 열지 않아 503이고, open()을 부른 뒤에야 201이다(D-6).")
    @Test
    void restarted_staysClosedUntilExplicitOpen() {
        Long campaignId = campaign();
        stopRedis();
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> whileStopped = issue(campaignId, 1L);

        startRedis();
        long reconnectMillis = awaitReconnected(admissionRedisTemplate);
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> afterPong = issue(campaignId, 1L);
        boolean openAfterPong = couponAdmission.isOpen();
        couponAdmission.open();
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> afterOpen = issue(campaignId, 1L);

        assertAll(
            () -> assertThat(whileStopped.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(afterPong.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(openAfterPong).isFalse(),
            () -> assertThat(afterOpen.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(tokens()).isEmpty()
        );
        log.info("[D-13 재시작] 재연결 대기 {}ms", reconnectMillis);
    }

    @DisplayName("commit 뒤 자리 반환이 실패하면, 응답은 201과 쿠폰 그대로, 수용은 닫히고 token이 남는다. D-5 순서로 정리하면 다시 201이다.")
    @Test
    void releaseFailure_keepsCoupon_closes_thenManualRecovery() throws Exception {
        Long campaignId = campaign();
        // 이음매: DB 발급이 끝난 뒤(이 빈에는 TX가 없어 돌아오면 commit 뒤다), 자리 반환 전에 멈춘다
        CountDownLatch issued = new CountDownLatch(1);
        gate = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            issued.countDown();
            if (!gate.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("이음매 대기 deadline 초과");
            }
            return result;
        }).when(couponIssuer).issue(eq(campaignId), eq(1L));

        Future<ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>>> pending = executor.submit(() -> issue(campaignId, 1L));
        assertThat(issued.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).isTrue();
        Set<String> held = tokens();
        assertAll(
            () -> assertThat(held).hasSize(1),
            () -> assertThat(admissionRedisTemplate.getExpire(RedisAdmissionPermits.PERMITS_KEY)).isEqualTo(-1L),
            () -> assertThat(couponRows(campaignId)).isEqualTo(1L)
        );
        String token = held.iterator().next();

        // act — Redis를 멈춘 뒤 실행을 풀면 반환(SREM)이 실패한다
        stopRedis();
        gate.countDown();
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> response = pending.get(DEADLINE_SECONDS, TimeUnit.SECONDS);

        Long storedCouponId = jdbcTemplate.queryForObject(
            "SELECT id FROM issued_coupon WHERE campaign_id = ? AND user_id = ?", Long.class, campaignId, 1L);
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(response.getBody().data().couponId()).isEqualTo(storedCouponId),
            () -> assertThat(couponAdmission.isOpen()).isFalse(),
            () -> assertThat(couponRows(campaignId)).isEqualTo(1L),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(1)
        );

        // Redis가 돌아오면 반환하지 못한 token이 만료 없이 남아 있다
        startRedis();
        long reconnectMillis = awaitReconnected(admissionRedisTemplate);
        assertAll(
            () -> assertThat(tokens()).containsExactly(token),
            () -> assertThat(admissionRedisTemplate.getExpire(RedisAdmissionPermits.PERMITS_KEY)).isEqualTo(-1L),
            () -> assertThat(couponAdmission.isOpen()).isFalse()
        );

        // D-5: 닫기 → DB 확인 → 자기 범위 token만 지우기 → 열기
        couponAdmission.close();
        assertAll(
            () -> assertThat(couponRows(campaignId)).isEqualTo((long) issuedCount(campaignId)),
            () -> assertThat(token).startsWith(couponAdmission.serverId() + ":")
        );
        couponAdmission.releaseLeftover(token);
        assertThat(tokens()).isEmpty();
        couponAdmission.open();

        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> next = issue(campaignId, 2L);
        assertAll(
            () -> assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(issuedCount(campaignId)).isEqualTo(2),
            () -> assertThat(tokens()).isEmpty()
        );
        log.info("[D-13 반환 실패] 재연결 대기 {}ms", reconnectMillis);
    }
}
