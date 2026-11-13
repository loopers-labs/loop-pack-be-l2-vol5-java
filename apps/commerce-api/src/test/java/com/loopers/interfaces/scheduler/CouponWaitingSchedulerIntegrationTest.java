package com.loopers.interfaces.scheduler;

import com.loopers.fixture.CouponCacheMiss;
import org.springframework.context.annotation.Import;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository.StartOutcome;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 실제 CouponWaitingScheduler·fixture Redis·MySQL 전용 컨텍스트에서 자동 회차만으로 입장 승격·미사용 READY 회수·다음 대기자 승격을 본다
 * (설계 §12.4·12.13 WQ11). 테스트는 processAdmissions·GET 을 부르지 않는다. delay 100ms·입장 기한 1.5초는 관찰용 짧은 값이며
 * 제품 기본 1초/30초 바인딩은 CouponWaitingPropertiesTest 가 본다. 저장소는 실제 구현을 감싼 spy 로 호출만 센다.
 *
 * <p>운영 캠페인 키 5개가 모두 비었을 때만 소유하고, 끝나면 수용을 닫고 scheduler 를 멈춘 뒤 소유한 키만 지운다.
 */
@SpringBootTest
@Import(CouponCacheMiss.class) // 캐시 도입 전 계약 검사. 조회 miss·저장 no-op(설계 §13.12 C1)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실제 자동 회차는 닫힘에서 업무가 없고, 열리면 등록자를 C 이내로 승격하며, 미사용 READY 를 회수해 다음 대기자를 올리고 PROCESSING 은 남긴다.")
class CouponWaitingSchedulerIntegrationTest {

    private static final long OPERATING = 9_005L;
    private static final Duration DELAY = Duration.ofMillis(100);
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
        registry.add("commerce.coupon.waiting.campaign-id", () -> String.valueOf(OPERATING));
        registry.add("commerce.coupon.waiting.scheduler-delay", () -> DELAY.toMillis() + "ms");
        registry.add("commerce.coupon.waiting.admission-duration", () -> "1500ms");
    }

    @MockitoSpyBean
    private CouponAdmissionRedisRepository waitingRepository;
    @Autowired
    private CouponWaitingScheduler scheduler;
    @Autowired
    private CouponFacade couponFacade;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;

    private boolean ownsKeys;
    private ExecutorSettlement settlement;

    @BeforeEach
    void setUp() {
        CouponRedisTarget.verify(redisProperties, master);
        settlement = ExecutorSettlement.capture(scheduler); // 본문이 먼저 stop 해도 원래 실행기로 종료를 판단한다.
        for (String key : CouponAdmissionRedisRepository.waitingKeys(OPERATING)) {
            assertThat(master.type(key)).as("시작 전 대기실 키가 비어 있어야 한다. 지우지 않는다: " + key).isEqualTo(DataType.NONE);
        }
        ownsKeys = true;
    }

    @AfterEach
    void tearDown() {
        couponFacade.close();
        if (settlement == null) {
            scheduler.stop(); // 대상 확인 전 실패. 소유한 자원이 없어 정리하지 않는다.
            return;
        }
        settlement.stopAndCleanup(scheduler::stop, DEADLINE, () -> {
            if (ownsKeys) {
                master.delete(CouponAdmissionRedisRepository.waitingKeys(OPERATING));
            }
            databaseCleanUp.truncateAllTables();
        });
    }

    /**
     * scheduler 가 소유한 실행기의 실제 종료를 확인한 뒤에만 Redis·DB 정리를 허용한다. stop 반환·isRunning=false·executor 필드 null 은
     * 진행 회차 종료의 증거가 아니다(stop 은 한도 초과·interrupt 에도 반환한다). 그래서 stop 전에 보관한 원래 참조로 판단한다.
     * 참조가 없거나 한도 안에 종료되지 않거나 대기가 interrupt 되면 정리하지 않고 실패한다(interrupt 상태는 복원).
     */
    static final class ExecutorSettlement {
        private final ScheduledExecutorService executor;

        ExecutorSettlement(ScheduledExecutorService executor) {
            this.executor = executor;
        }

        static ExecutorSettlement capture(CouponWaitingScheduler scheduler) {
            return new ExecutorSettlement((ScheduledExecutorService) ReflectionTestUtils.getField(scheduler, "executor"));
        }

        void stopAndCleanup(Runnable stop, Duration wait, Runnable cleanup) {
            stop.run();
            if (executor == null) {
                throw new IllegalStateException("실행기 참조를 확보하지 못해 종료 미확인. Redis·DB 정리를 보류한다.");
            }
            boolean terminated;
            try {
                terminated = executor.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("실행기 종료 대기 중 interrupt: 종료 미확인. Redis·DB 정리를 보류한다.", e);
            }
            if (!terminated) {
                throw new IllegalStateException("실행기가 " + wait + " 안에 종료되지 않음: 진행 회차 종료 미확인. Redis·DB 정리를 보류한다.");
            }
            cleanup.run();
        }
    }

    private String status(Long user) {
        Object raw = master.opsForHash().get("waiting:entries:v1:" + OPERATING, String.valueOf(user));
        if (raw == null) {
            return "NONE";
        }
        String json = (String) raw;
        return json.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
    }

    private long occupancy() {
        Long ready = master.opsForZSet().size("waiting:ready:v1:" + OPERATING);
        Long processing = master.opsForSet().size("waiting:processing:v1:" + OPERATING);
        return (ready == null ? 0 : ready) + (processing == null ? 0 : processing);
    }

    /** 조건을 유한하게 기다리며 그동안 READY+PROCESSING 이 C 를 넘지 않았는지도 센다. */
    private long awaitWhileSampling(BooleanSupplier condition, String what) throws InterruptedException {
        long maxOccupancy = 0;
        long until = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            maxOccupancy = Math.max(maxOccupancy, occupancy());
            assertThat(System.nanoTime()).as(what + " 대기 초과").isLessThan(until);
            Thread.sleep(20);
        }
        return Math.max(maxOccupancy, occupancy());
    }

    private int processCalls() {
        return (int) mockingDetails(waitingRepository).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("processAdmissions")).count();
    }

    @DisplayName("WQ11: 닫힘 동안 Redis 입장 처리 0 → open 뒤 자동 승격(C=3) → 미사용 READY 만료 회수·넷째 승격, PROCESSING 미회수·DB 쓰기 0. stop 뒤 실행기 종료·새 회차 없음.")
    @Test
    void automaticRoundsPromoteAndReclaim() throws Exception {
        fixture.operatingCampaign(OPERATING, 10);
        List<Long> users = fixture.users(4);
        assertThat(scheduler.isRunning()).as("컨텍스트 시작으로 회차가 돈다").isTrue();
        Thread.sleep(DELAY.toMillis() * 5);
        verify(waitingRepository, never()).processAdmissions(anyLong(), anyInt(), anyLong(), anyBoolean(), anyBoolean());

        couponFacade.open();
        Map<Long, String> ids = new LinkedHashMap<>();
        for (Long user : users) {
            ids.put(user, couponFacade.registerWaiting(user, OPERATING).snapshot().waitingId());
        }
        long firstMax = awaitWhileSampling(() -> occupancy() == 3, "자동 승격 3명");
        Long first = users.get(0);
        Long fourth = users.get(3);
        List<String> afterPromotion = new ArrayList<>();
        users.forEach(user -> afterPromotion.add(status(user)));
        assertThat(waitingRepository.start(OPERATING, first, ids.get(first)).outcome()).isEqualTo(StartOutcome.STARTED);

        long secondMax = awaitWhileSampling(() -> "READY".equals(status(fourth)) && "EXPIRED".equals(status(users.get(1))),
            "만료 회수·넷째 승격");
        Thread.sleep(1_600); // 첫째의 입장 기한도 지나게 둔다. PROCESSING 은 회수되지 않아야 한다.
        String firstStatus = status(first);

        ScheduledExecutorService executor = (ScheduledExecutorService) ReflectionTestUtils.getField(scheduler, "executor");
        scheduler.stop();
        int callsAtStop = processCalls();
        Thread.sleep(DELAY.toMillis() * 5);

        System.out.printf("[coupon-wq11] campaign=%d afterPromotion=%s firstMax=%d secondMax=%d final=%s/%s/%s/%s processCalls=%d%n",
            OPERATING, afterPromotion, firstMax, secondMax, status(first), status(users.get(1)), status(users.get(2)), status(fourth),
            callsAtStop);
        assertAll(
            () -> assertThat(afterPromotion).containsExactly("READY", "READY", "READY", "WAITING"),
            () -> assertThat(firstMax).as("C=3 상한").isLessThanOrEqualTo(3),
            () -> assertThat(secondMax).isLessThanOrEqualTo(3),
            () -> assertThat(status(users.get(2))).isEqualTo("EXPIRED"),
            () -> assertThat(firstStatus).as("기한이 지나도 PROCESSING 미회수").isEqualTo("PROCESSING"),
            () -> assertThat(master.opsForSet().members("waiting:processing:v1:" + OPERATING)).containsExactly(String.valueOf(first)),
            () -> assertThat(fixture.couponCount(OPERATING)).as("scheduler 는 쿠폰을 만들지 않는다").isZero(),
            () -> assertThat(fixture.issuedCount(OPERATING)).isZero(),
            () -> assertThat(executor).isNotNull(),
            () -> assertThat(executor.isTerminated()).as("소유 스레드 종료").isTrue(),
            () -> assertThat(processCalls()).as("stop 뒤 새 회차 없음").isEqualTo(callsAtStop),
            () -> assertThat(scheduler.isRunning()).isFalse()
        );
    }
}
