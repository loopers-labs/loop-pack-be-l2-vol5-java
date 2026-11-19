package com.loopers.application.coupon;

import com.loopers.config.coupon.CouponAdmissionRedisConfig;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionGate.CloseReason;
import com.loopers.domain.coupon.CouponAdmissionGate.Closure;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 8주차 설계 INV-W8-05·INV-W8-10 ①. 실제 Redis 중단 장면.
 * ProductCacheRedisOutageTest 와 같이 이 클래스만 쓰는 전용 Redis 컨테이너를 고정 호스트 포트로 띄우고 datasource.redis 를 그쪽으로 돌린다.
 * 쿠폰 전용 연결(CouponAdmissionRedisConfig)이 그 주소를 쓰는지 테스트 안에서 확인한 뒤 컨테이너를 멈춘다.
 * stop() 은 컨테이너를 지우므로 재기동 뒤 데이터가 비어 있다(상태 유실 재현). 다른 테스트 클래스의 공용 Redis 에는 영향이 없다.
 */
@SpringBootTest
class CouponAdmissionRedisOutageTest {

    private static final int DEDICATED_PORT = findFreePort();

    @SuppressWarnings({"deprecation", "resource"})
    private static final GenericContainer<?> DEDICATED_REDIS = new FixedHostPortGenericContainer<>("redis:7.0")
        .withFixedExposedPort(DEDICATED_PORT, 6379);

    static {
        DEDICATED_REDIS.start();
    }

    @DynamicPropertySource
    static void pointRedisToDedicatedContainer(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", DEDICATED_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> DEDICATED_PORT);
        registry.add("datasource.redis.replicas[0].host", DEDICATED_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> DEDICATED_PORT);
    }

    @AfterAll
    static void stopDedicatedContainer() {
        if (DEDICATED_REDIS.isRunning()) {
            DEDICATED_REDIS.stop();
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("빈 포트를 찾지 못했다", e);
        }
    }

    /** commandTimeout 300ms + 연결 시도 500ms 보다 조금 넉넉하게. 수 초가 걸리면 설정이 적용되지 않은 것이다. */
    private static final Duration OUTAGE_RESPONSE_LIMIT = Duration.ofMillis(1_500);
    private static final Duration RECONNECT_LIMIT = Duration.ofSeconds(15);
    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));

    private final CouponAdmissionFacade facade;
    private final CouponAdmissionGate gate;
    private final CouponAdmissionQueue queue;
    private final LettuceConnectionFactory couponConnectionFactory;
    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponJpaRepository couponJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    CouponAdmissionRedisOutageTest(
        CouponAdmissionFacade facade,
        CouponAdmissionGate gate,
        CouponAdmissionQueue queue,
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_CONNECTION_FACTORY) LettuceConnectionFactory couponConnectionFactory,
        CouponCampaignRepository couponCampaignRepository,
        CouponJpaRepository couponJpaRepository,
        UserJpaRepository userJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.facade = facade;
        this.gate = gate;
        this.queue = queue;
        this.couponConnectionFactory = couponConnectionFactory;
        this.couponCampaignRepository = couponCampaignRepository;
        this.couponJpaRepository = couponJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private record Timed<T>(T value, Throwable error, Duration elapsed) { }

    private static <T> Timed<T> timed(Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return new Timed<>(call.get(), null, Duration.ofNanos(System.nanoTime() - start));
        } catch (RuntimeException e) {
            return new Timed<>(null, e, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private static void assertServiceUnavailable(Timed<?> result) {
        assertThat(result.error()).isInstanceOf(CoreException.class);
        assertThat(((CoreException) result.error()).getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE);
        assertThat(result.elapsed()).as("응답 시간은 commandTimeout(300ms) 수준이어야 한다").isLessThan(OUTAGE_RESPONSE_LIMIT);
    }

    private void awaitReconnect() throws InterruptedException {
        long deadline = System.currentTimeMillis() + RECONNECT_LIMIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if ("PONG".equals(couponConnectionFactory.getConnection().ping())) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // 아직 연결 전
            }
            Thread.sleep(200);
        }
        throw new AssertionError("재기동한 Redis 에 " + RECONNECT_LIMIT + " 안에 다시 연결되지 않았다");
    }

    @DisplayName("INV-W8-05 Redis 중단 중 접수·claim 은 commandTimeout 안에 503 이고 DB 신규 효과가 없다. 보유 목록은 정상. gate 는 REDIS_ERROR 로 닫힌다. "
        + "재기동(데이터 유실) 뒤 자동으로 열리지 않고, 관리자 open 후에만 접수가 된다 (INV-W8-10 ①).")
    @Test
    void failsClosedDuringOutage_andRequiresAdminReopen() throws Exception {
        // arrange — 쿠폰 전용 연결이 전용 컨테이너를 쓰는지 먼저 확인한다
        assertThat(couponConnectionFactory.getHostName()).isEqualTo(DEDICATED_REDIS.getHost());
        assertThat(couponConnectionFactory.getPort()).isEqualTo(DEDICATED_PORT);
        assertThat(couponConnectionFactory.getConnection().ping()).isEqualTo("PONG");

        Long campaignId = couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", 10, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT)).getId();
        String gen = gate.open(campaignId);
        Long issuedUser = userJpaRepository.save(new User()).getId();
        Long admittedUser = userJpaRepository.save(new User()).getId();
        Long newUser = userJpaRepository.save(new User()).getId();
        facade.apply(campaignId, issuedUser);
        facade.apply(campaignId, admittedUser);
        queue.promote(campaignId, gen);
        Long couponId = facade.claim(campaignId, issuedUser).couponId();
        int issuedBefore = couponCampaignRepository.findById(campaignId).orElseThrow().getIssuedCount();
        long rowsBefore = couponJpaRepository.count();

        // act — 중단
        DEDICATED_REDIS.stop();
        assertThat(DEDICATED_REDIS.isRunning()).isFalse();

        Timed<?> apply = timed(() -> facade.apply(campaignId, newUser));
        Timed<?> claim = timed(() -> facade.claim(campaignId, admittedUser));
        Timed<List<Coupon>> coupons = timed(() -> facade.myCoupons(issuedUser));

        // assert — a·b·c·e
        assertServiceUnavailable(apply);
        assertServiceUnavailable(claim);
        assertThat(coupons.error()).isNull();
        assertThat(coupons.value()).extracting(Coupon::getId).containsExactly(couponId);
        assertThat(couponCampaignRepository.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(issuedBefore);
        assertThat(couponJpaRepository.count()).isEqualTo(rowsBefore);
        assertThat(gate.isOpen(campaignId)).isFalse();
        assertThat(gate.closure(campaignId)).map(Closure::reason).contains(CloseReason.REDIS_ERROR);
        Timed<?> afterClose = timed(() -> facade.apply(campaignId, newUser));
        assertServiceUnavailable(afterClose);
        System.out.printf("[INV-W8-05] 중단 중 응답 시간 apply=%dms claim=%dms apply(gate 닫힌 뒤)=%dms%n",
            apply.elapsed().toMillis(), claim.elapsed().toMillis(), afterClose.elapsed().toMillis());

        // act — 재기동. stop() 은 컨테이너를 지우므로 Redis 데이터는 비어 있다(상태 유실)
        DEDICATED_REDIS.start();
        awaitReconnect();

        // assert — d
        assertThat(gate.isOpen(campaignId)).as("PING 이 되어도 자동으로 열리지 않는다").isFalse();
        assertThatThrownBy(() -> facade.apply(campaignId, newUser))
            .isInstanceOf(CoreException.class)
            .satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
        for (int i = 0; i < 3; i++) {
            assertThat(facade.probeOnce()).as("auto-reopen 기본 꺼짐").isZero();
        }
        assertThat(gate.isOpen(campaignId)).isFalse();

        facade.openGate(campaignId);

        assertThat(gate.isOpen(campaignId)).isTrue();
        assertThat(facade.apply(campaignId, newUser).outcome()).isEqualTo(CouponAdmissionFacade.ApplyOutcome.ACCEPTED);
        assertThat(facade.status(campaignId, admittedUser).status()).as("유실된 승격은 복원되지 않는다").isEqualTo(CouponAdmissionFacade.Status.NONE);
        assertThat(facade.myCoupons(issuedUser)).extracting(Coupon::getId).containsExactly(couponId);
    }
}
