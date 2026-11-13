package com.loopers.interfaces.experiment;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.loopers.application.coupon.facade.CouponAdmission;
import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.AdmissionSlots;
import com.loopers.support.CouponTables;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.DockerClientFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 세마포어 실험 (과제 5 · 6장, 8주차 D-157 ~ D-162). 실제 Redis · MySQL 과 실제 HTTP 로 확인한다.
 * 실행 흐름은 테스트 소스의 실험용 컨트롤러가 조립하고, 수용 · 반환은 운영 코드의 CouponAdmission 이 한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponAdmissionTest {

    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CouponAdmission admission;

    @MockitoSpyBean
    private AdmissionSlots admissionSlots;

    @Autowired
    private AdmissionBarrier barrier;

    @Autowired
    private CouponTables couponTables;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier(CouponRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUp() {
        admission.open();
    }

    @AfterEach
    void tearDown() {
        barrier.releaseAll();
        executor.shutdownNow();
        couponTables.truncate();
        redisCleanUp.truncateAll();
    }

    private ResponseEntity<Map<String, Object>> post(long campaignId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return rest.exchange("/experiment/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(headers), BODY);
    }

    private static String slotKey(long campaignId) {
        return "coupon:admission:{" + campaignId + "}";
    }

    private long activeTokens(long campaignId) {
        Long size = redis.opsForSet().size(slotKey(campaignId));
        return size == null ? 0 : size;
    }

    private long couponsOf(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE user_id = ?", Long.class, userId);
    }

    @DisplayName("C=3 에서 세 실행을 붙잡으면 네 번째는 429 이고 DB 에 들어가지 않는다. 하나가 끝나 반환하면 네 번째가 그 자리로 발급된다.")
    @Test
    void rejectsFourth_whileThreeHeld_thenReusesReleasedSlot() throws Exception {
        // arrange: 발급 한도는 실험을 가리지 않게 넉넉히
        long campaignId = couponTables.insertOpenCampaign(10);
        barrier.hold(3);
        List<Future<ResponseEntity<Map<String, Object>>>> held = new ArrayList<>();
        for (long userId = 1; userId <= 3; userId++) {
            long user = userId;
            held.add(executor.submit(() -> post(campaignId, user)));
        }
        assertThat(barrier.awaitArrivals()).isTrue();
        long tokensWhileHeld = activeTokens(campaignId);

        // act 1: 네 번째
        ResponseEntity<Map<String, Object>> fourth = post(campaignId, 4);
        long fourthCoupons = couponsOf(4);

        // act 2: 하나를 보내 발급 · 반환
        barrier.passOne();
        HttpStatus firstDone = HttpStatus.valueOf(firstCompleted(held).getStatusCode().value());
        long tokensAfterOne = activeTokens(campaignId);
        ResponseEntity<Map<String, Object>> fourthRetry = post(campaignId, 4);

        // act 3: 나머지도 끝냄
        barrier.releaseAll();
        for (Future<ResponseEntity<Map<String, Object>>> future : held) {
            assertThat(future.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        // assert
        assertAll(
            () -> assertThat(tokensWhileHeld).isEqualTo(3),
            () -> assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS),
            () -> assertThat(fourthCoupons).isZero(),
            () -> assertThat(firstDone).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(tokensAfterOne).isEqualTo(2),
            () -> assertThat(fourthRetry.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Long.class, campaignId)).isEqualTo(4L),
            () -> assertThat(activeTokens(campaignId)).isZero()
        );
    }

    private static ResponseEntity<Map<String, Object>> firstCompleted(List<Future<ResponseEntity<Map<String, Object>>>> futures)
        throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                if (future.isDone()) {
                    return future.get();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("붙잡힌 실행 하나가 끝나지 않음");
    }

    @DisplayName("자기 token 만 반환한다. 두 번 반환하거나 모르는 token 을 반환해도 다른 자리는 남는다.")
    @Test
    void releasesOnlyOwnToken() {
        // arrange
        admissionSlots.acquire(1L, 3, "streamer-1:a");
        admissionSlots.acquire(1L, 3, "streamer-1:b");

        // act
        admissionSlots.release(1L, "streamer-1:a");
        admissionSlots.release(1L, "streamer-1:a");
        admissionSlots.release(1L, "streamer-1:unknown");

        // assert
        assertThat(redis.opsForSet().members(slotKey(1L))).containsExactly("streamer-1:b");
    }

    @DisplayName("자리는 TTL 로 자동 반환되지 않는다.")
    @Test
    void slotsHaveNoTtl() {
        admissionSlots.acquire(1L, 3, "streamer-1:a");

        assertThat(redis.getExpire(slotKey(1L))).isEqualTo(-1L);
    }

    @DisplayName("실제 Redis 가 멈추면 503 이고 수용이 닫힌다. Redis 가 돌아와도 자동으로 열리지 않고, 확인 뒤 재개해야 발급된다.")
    @Test
    void closesOnRedisOutage_andNeedsExplicitResume() throws Exception {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(10);
        Container container = redisContainer();
        DockerClient docker = DockerClientFactory.instance().client();

        // act
        ResponseEntity<Map<String, Object>> duringOutage;
        docker.pauseContainerCmd(container.getId()).exec();
        try {
            duringOutage = post(campaignId, 1);
        } finally {
            docker.unpauseContainerCmd(container.getId()).exec();
        }
        ResponseEntity<Map<String, Object>> afterRecovery = post(campaignId, 1);
        boolean resumed = admission.resume(campaignId);
        ResponseEntity<Map<String, Object>> afterResume = post(campaignId, 1);

        // assert
        assertAll(
            () -> assertThat(duringOutage.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(afterRecovery.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(resumed).isTrue(),
            () -> assertThat(afterResume.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(couponsOf(1)).isEqualTo(1L)
        );
    }

    @DisplayName("반환이 실패해도 발급한 쿠폰은 그대로이고 수용이 닫힌다. 재개는 자기 범위의 남은 token 만 정리한다.")
    @Test
    void keepsCoupon_whenReleaseFails_andResumeCleansOnlyOwnTokens() {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(10);
        doThrow(new IllegalStateException("redis down")).doCallRealMethod()
            .when(admissionSlots).release(anyLong(), anyString());
        redis.opsForSet().add(slotKey(campaignId), "other-instance:alive");

        // act
        ResponseEntity<Map<String, Object>> issued = post(campaignId, 1);
        boolean openAfterFailure = admission.isOpen();
        long leftOwnTokens = redis.opsForSet().members(slotKey(campaignId)).stream().filter(token -> token.startsWith("streamer-1:")).count();
        boolean resumed = admission.resume(campaignId);

        // assert
        assertAll(
            () -> assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(couponsOf(1)).isEqualTo(1L),
            () -> assertThat(openAfterFailure).isFalse(),
            () -> assertThat(leftOwnTokens).isEqualTo(1L),
            () -> assertThat(resumed).isTrue(),
            () -> assertThat(redis.opsForSet().members(slotKey(campaignId))).containsExactly("other-instance:alive")
        );
    }

    @DisplayName("이 인스턴스의 실행이 남아 있으면 재개하지 않는다.")
    @Test
    void doesNotResume_whileExecutionRunning() throws Exception {
        // arrange
        long campaignId = couponTables.insertOpenCampaign(10);
        barrier.hold(1);
        Future<ResponseEntity<Map<String, Object>>> running = executor.submit(() -> post(campaignId, 1));
        assertThat(barrier.awaitArrivals()).isTrue();

        // act
        boolean resumed = admission.resume(campaignId);
        barrier.releaseAll();

        // assert
        assertAll(
            () -> assertThat(resumed).isFalse(),
            () -> assertThat(running.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED)
        );
    }

    /** 앱이 실제로 연결한 Redis 컨테이너를 매핑 포트로 찾는다. 엉뚱한 컨테이너를 멈추지 않는다 */
    private static Container redisContainer() {
        int port = Integer.parseInt(System.getProperty("datasource.redis.master.port"));
        return DockerClientFactory.instance().client().listContainersCmd().exec().stream()
            .filter(container -> Arrays.stream(container.getPorts())
                .anyMatch(binding -> binding.getPublicPort() != null && binding.getPublicPort() == port))
            .findFirst()
            .orElseThrow(() -> new AssertionError("앱이 연결한 Redis 컨테이너를 찾지 못함. port=" + port));
    }
}
