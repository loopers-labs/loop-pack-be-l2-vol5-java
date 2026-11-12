package com.loopers.interfaces.scheduler;

import com.loopers.fixture.CouponCacheMiss;
import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.interfaces.scheduler.CouponWaitingSchedulerIntegrationTest.ExecutorSettlement;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 쿠폰 입장 scheduler 의 내부 실행기·회차 간격·종료·예외 처리(설계 §12.11 F4, WQ11 의 lifecycle 부분). Facade 는 대역이거나
 * 대역 저장소 위의 실제 Facade/Service 다. 실제 Redis·DB 의 자동 승격·만료는 CouponWaitingSchedulerIntegrationTest 가 본다.
 * 시간은 하한(최초 delay·회차 간격)만 단언하고, 스레드 스케줄링 때문에 흔들리는 상한은 두지 않는다. 대기는 모두 유한하다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("입장 scheduler 는 운영 캠페인이 있을 때만 단일 실행기로 회차를 겹치지 않게 돌리고, 종료 때 진행 회차를 유한하게 기다린다.")
class CouponWaitingSchedulerLifecycleTest {

    private static final long CAMPAIGN = 7L;
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    private final CouponFacade facade = mock(CouponFacade.class);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<long[]> rounds = new CopyOnWriteArrayList<>();
    private final List<String> threadNames = new CopyOnWriteArrayList<>();
    private CouponWaitingScheduler scheduler;

    @AfterEach
    void stopAndRelease() throws InterruptedException {
        release.countDown();
        if (scheduler != null) {
            ScheduledExecutorService executor = executor();
            scheduler.stop();
            if (executor != null) {
                assertThat(executor.awaitTermination(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).as("소유 실행기 종료").isTrue();
            }
        }
    }

    private CouponWaitingScheduler scheduler(Long campaignId, Duration delay, String stopTimeout) {
        scheduler = new CouponWaitingScheduler(facade,
            new CouponWaitingProperties(campaignId, delay, Duration.ofSeconds(30)), stopTimeout);
        return scheduler;
    }

    private ScheduledExecutorService executor() {
        return (ScheduledExecutorService) ReflectionTestUtils.getField(scheduler, "executor");
    }

    /** 첫 회차만 release 까지 붙잡고, 모든 회차의 시작·종료 시각과 스레드 이름을 남긴다. */
    private void recordRounds(boolean holdFirst) {
        when(facade.processAdmissions()).thenAnswer(invocation -> {
            long[] round = {System.nanoTime(), 0};
            rounds.add(round);
            threadNames.add(Thread.currentThread().getName());
            if (holdFirst && rounds.size() == 1 && !release.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("회차 붙잡기 deadline 초과");
            }
            round[1] = System.nanoTime();
            return Optional.empty();
        });
    }

    private void awaitRounds(int count) throws InterruptedException {
        long until = System.nanoTime() + DEADLINE.toNanos();
        while (rounds.size() < count) {
            assertThat(System.nanoTime()).as(count + " 회차 대기 초과").isLessThan(until);
            Thread.sleep(5);
        }
    }

    private int processCalls() {
        return (int) mockingDetails(facade).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("processAdmissions")).count();
    }

    @DisplayName("운영 캠페인이 없으면 실행기를 만들지 않고 회차가 없다. start·stop 은 그대로 동작한다.")
    @Test
    void unconfiguredHasNoExecutor() throws Exception {
        CouponWaitingScheduler unconfigured = scheduler(null, Duration.ofMillis(50), "30s");

        unconfigured.start();
        Thread.sleep(300);

        assertAll(
            () -> assertThat(unconfigured.isRunning()).isTrue(),
            () -> assertThat(executor()).isNull(),
            () -> verifyNoInteractions(facade)
        );
        unconfigured.stop();
        assertThat(unconfigured.isRunning()).isFalse();
    }

    @DisplayName("최초 회차도 delay 뒤에 시작하고, 회차가 끝나기 전 다음 회차가 겹치지 않으며, 끝난 뒤 delay 를 두고 다음 회차를 coupon-waiting- 스레드에서 한다.")
    @Test
    void firstDelayFixedDelayNoOverlap() throws Exception {
        Duration delay = Duration.ofMillis(200);
        recordRounds(true);
        long started = System.nanoTime();
        scheduler(CAMPAIGN, delay, "30s").start();

        awaitRounds(1);
        Thread.sleep(delay.toMillis() * 3);
        int whileHeld = rounds.size();
        release.countDown();
        awaitRounds(2);

        long firstDelay = rounds.get(0)[0] - started;
        long gap = rounds.get(1)[0] - rounds.get(0)[1];
        assertAll(
            () -> assertThat(Duration.ofNanos(firstDelay)).isGreaterThanOrEqualTo(delay),
            () -> assertThat(whileHeld).as("붙잡힌 회차 동안 다음 회차 없음").isEqualTo(1),
            () -> assertThat(Duration.ofNanos(gap)).as("회차 종료 뒤 delay").isGreaterThanOrEqualTo(delay),
            () -> assertThat(threadNames).allSatisfy(name -> assertThat(name).startsWith("coupon-waiting-")),
            () -> assertThat(threadNames).as("단일 실행기").containsOnly(threadNames.get(0))
        );
    }

    @DisplayName("중복 start 는 실행기·예약을 하나로 유지하고, 중복 stop 은 안전하다.")
    @Test
    void duplicateStartAndStop() {
        recordRounds(false);
        CouponWaitingScheduler subject = scheduler(CAMPAIGN, Duration.ofMillis(50), "30s");
        subject.start();
        Object executor = executor();
        Object future = ReflectionTestUtils.getField(subject, "rounds");

        subject.start();

        assertAll(
            () -> assertThat(executor()).isSameAs(executor),
            () -> assertThat(ReflectionTestUtils.getField(subject, "rounds")).isSameAs(future)
        );
        subject.stop();
        subject.stop();
        assertThat(subject.isRunning()).isFalse();
    }

    @DisplayName("stop 은 진행 중 회차를 끊지 않고 끝날 때까지 기다린 뒤 실행기를 종료하며, 그 뒤 새 회차가 없다.")
    @Test
    void stopWaitsForRunningRound(CapturedOutput output) throws Exception {
        recordRounds(true);
        CouponWaitingScheduler subject = scheduler(CAMPAIGN, Duration.ofMillis(50), "30s");
        subject.start();
        awaitRounds(1);
        ScheduledExecutorService executor = executor();

        Thread stopper = new Thread(subject::stop);
        stopper.start();
        Thread.sleep(300);
        boolean stopperWaited = stopper.isAlive();
        release.countDown();
        stopper.join(DEADLINE.toMillis());
        int afterStop = processCalls();
        Thread.sleep(300);

        assertAll(
            () -> assertThat(stopperWaited).as("진행 회차 종료 대기").isTrue(),
            () -> assertThat(stopper.isAlive()).isFalse(),
            () -> assertThat(executor.isTerminated()).as("소유 스레드 종료").isTrue(),
            () -> assertThat(processCalls()).as("stop 뒤 새 회차 없음").isEqualTo(afterStop),
            () -> assertThat(subject.isRunning()).isFalse(),
            () -> assertThat(output).contains("COUPON_WAITING_SCHEDULER_STOPPED").doesNotContain("STOP_INCOMPLETE")
        );
    }

    @DisplayName("진행 회차가 lifecycle 한도 안에 끝나지 않으면 미완료로 기록하고 정상 종료로 보고하지 않는다.")
    @Test
    void stopTimeoutIsIncomplete(CapturedOutput output) throws Exception {
        recordRounds(true);
        CouponWaitingScheduler subject = scheduler(CAMPAIGN, Duration.ofMillis(50), "200ms");
        subject.start();
        awaitRounds(1);
        ScheduledExecutorService executor = executor();

        subject.stop();

        assertAll(
            () -> assertThat(executor.isTerminated()).isFalse(),
            () -> assertThat(output).contains("COUPON_WAITING_SCHEDULER_STOP_INCOMPLETE").doesNotContain("COUPON_WAITING_SCHEDULER_STOPPED")
        );
        release.countDown();
        assertThat(executor.awaitTermination(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @DisplayName("종료 대기 중 interrupt 는 미확인으로 기록하고 interrupt 상태를 복원한다.")
    @Test
    void stopInterruptedRestoresFlag(CapturedOutput output) throws Exception {
        recordRounds(true);
        CouponWaitingScheduler subject = scheduler(CAMPAIGN, Duration.ofMillis(50), "30s");
        subject.start();
        awaitRounds(1);
        ScheduledExecutorService executor = executor();

        Thread.currentThread().interrupt();
        subject.stop();
        boolean restored = Thread.interrupted();

        assertAll(
            () -> assertThat(restored).isTrue(),
            () -> assertThat(output).contains("COUPON_WAITING_SCHEDULER_STOP_INTERRUPTED")
        );
        release.countDown();
        assertThat(executor.awaitTermination(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    @DisplayName("회차의 RuntimeException 은 로그 후 다음 회차를 유지하고, Error 는 로그 후 이후 회차를 멈춘다(자동 재시작 없음).")
    @Test
    void runtimeContinuesErrorStops(CapturedOutput output) throws Exception {
        when(facade.processAdmissions()).thenThrow(new IllegalStateException("회차 실패(대역)")).thenReturn(Optional.empty());
        scheduler(CAMPAIGN, Duration.ofMillis(50), "30s").start();
        verify(facade, timeout(DEADLINE.toMillis()).atLeast(3)).processAdmissions();
        scheduler.stop();
        assertThat(output).contains("COUPON_WAITING_ROUND_FAILED");

        CouponFacade failing = mock(CouponFacade.class);
        when(failing.processAdmissions()).thenThrow(new AssertionError("회차 Error(대역)"));
        CouponWaitingScheduler erroring = new CouponWaitingScheduler(failing,
            new CouponWaitingProperties(CAMPAIGN, Duration.ofMillis(50), Duration.ofSeconds(30)), "30s");
        erroring.start();
        try {
            verify(failing, timeout(DEADLINE.toMillis())).processAdmissions();
            Thread.sleep(500);
            assertAll(
                () -> verify(failing, atLeast(1)).processAdmissions(),
                () -> assertThat(mockingDetails(failing).getInvocations()).as("Error 뒤 회차 없음").hasSize(1),
                () -> assertThat(output).contains("COUPON_WAITING_ROUND_ERROR")
            );
        } finally {
            erroring.stop();
        }
    }

    @DisplayName("실제 Service·Facade: 닫힘이면 회차가 돌아도 DB·Redis 업무 호출이 없고, open 뒤 자동 회차가 입장 처리를 부르며, Redis 실패면 수용이 닫혀 이후 업무가 없다.")
    @Test
    void closedHasNoBusinessThenOpenThenRedisFailureCloses() throws Exception {
        CouponCampaignRepository campaigns = mock(CouponCampaignRepository.class);
        CouponAdmissionRepository waiting = mock(CouponAdmissionRepository.class);
        CouponCampaignModel campaign = mock(CouponCampaignModel.class);
        when(campaign.isActive()).thenReturn(true);
        when(campaign.isSoldOut()).thenReturn(false);
        when(campaigns.findById(CAMPAIGN)).thenReturn(Optional.of(campaign));
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        when(waiting.processAdmissions(eq(CAMPAIGN), eq(3), anyLong(), anyBoolean(), anyBoolean())).thenAnswer(invocation -> {
            if (fail.get()) {
                throw new QueryTimeoutException("Redis timeout(대역)");
            }
            return new AdmissionProgress(0, 0);
        });
        CouponWaitingProperties properties = new CouponWaitingProperties(CAMPAIGN, Duration.ofMillis(50), Duration.ofSeconds(30));
        CouponAdmissionService admission = new CouponAdmissionService(waiting, new CouponAdmissionProperties(3), properties);
        CouponFacade real = spy(new CouponFacade(mock(PlatformTransactionManager.class), mock(CouponQueryRepository.class), campaigns,
            mock(CouponRepository.class), new ConstraintViolationClassifier(), admission, CouponCacheMiss.service()));
        scheduler = new CouponWaitingScheduler(real, properties, "30s");
        scheduler.start();

        verify(real, timeout(DEADLINE.toMillis()).atLeast(3)).processAdmissions();
        verifyNoInteractions(campaigns, waiting);

        admission.open();
        verify(waiting, timeout(DEADLINE.toMillis()).atLeast(2)).processAdmissions(eq(CAMPAIGN), anyInt(), anyLong(), anyBoolean(), anyBoolean());

        fail.set(true);
        long until = System.nanoTime() + DEADLINE.toNanos();
        while (admission.isAccepting()) {
            assertThat(System.nanoTime()).as("Redis 실패 뒤 닫힘 대기 초과").isLessThan(until);
            Thread.sleep(10);
        }
        int redisCalls = (int) mockingDetails(waiting).getInvocations().size();
        int facadeCalls = (int) mockingDetails(real).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("processAdmissions")).count();
        Thread.sleep(300);
        int facadeCallsLater = (int) mockingDetails(real).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("processAdmissions")).count();
        assertAll(
            () -> assertThat(mockingDetails(waiting).getInvocations()).as("닫힌 뒤 Redis 업무 없음").hasSize(redisCalls),
            () -> assertThat(facadeCallsLater).as("회차는 계속").isGreaterThan(facadeCalls),
            () -> assertThat(admission.isAccepting()).as("자동으로 다시 열지 않음").isFalse(),
            () -> verify(campaigns, atLeastOnce()).findById(CAMPAIGN)
        );
    }

    @DisplayName("통합 테스트의 정리 판단: 회차가 붙잡혀 stop 이 한도(100ms)로 반환해도, 본문이 먼저 stop 했어도 원래 실행기 미종료면 정리하지 않고 실패한다. 대기 interrupt 도 보류·상태 복원, 참조가 없으면 보류다.")
    @Test
    void settlementWithholdsCleanupUntilOwnedExecutorTerminates() throws Exception {
        recordRounds(true);
        CouponWaitingScheduler subject = scheduler(CAMPAIGN, Duration.ofMillis(50), "100ms");
        subject.start();
        awaitRounds(1);
        ExecutorSettlement settlement = ExecutorSettlement.capture(subject);
        ScheduledExecutorService owned = executor();
        AtomicInteger cleanups = new AtomicInteger();
        try {
            assertThatThrownBy(() -> settlement.stopAndCleanup(subject::stop, Duration.ofMillis(200), cleanups::incrementAndGet))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("종료되지 않음");
            assertAll(
                () -> assertThat(executor()).as("stop 뒤 필드는 null 이지만 종료 증거가 아니다").isNull(),
                () -> assertThat(owned.isTerminated()).isFalse(),
                () -> assertThat(cleanups).hasValue(0)
            );

            assertThatThrownBy(() -> settlement.stopAndCleanup(subject::stop, Duration.ofMillis(200), cleanups::incrementAndGet))
                .as("본문이 먼저 stop 한 뒤의 정리 판단").isInstanceOf(IllegalStateException.class).hasMessageContaining("종료되지 않음");

            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> settlement.stopAndCleanup(subject::stop, Duration.ofSeconds(5), cleanups::incrementAndGet))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupt");
            assertThat(Thread.interrupted()).as("interrupt 상태 복원").isTrue();

            assertThatThrownBy(() -> new ExecutorSettlement(null).stopAndCleanup(() -> { }, Duration.ofMillis(200), cleanups::incrementAndGet))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("참조");
            assertThat(cleanups).as("어느 미확인 경로에서도 정리하지 않는다").hasValue(0);
        } finally {
            Thread.interrupted();
            release.countDown(); // 이 검사가 붙잡은 회차를 놓고 실제 소유 스레드 종료를 확인한다(정리 보류 판단과 별개의 장치 정리).
            assertThat(owned.awaitTermination(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @DisplayName("통합 테스트의 정리 판단: 원래 실행기가 종료되면 그때만 정리를 한 번 실행한다.")
    @Test
    void settlementCleansUpAfterOwnedExecutorTerminates() {
        recordRounds(false);
        CouponWaitingScheduler subject = scheduler(CAMPAIGN, Duration.ofMillis(50), "30s");
        subject.start();
        ExecutorSettlement settlement = ExecutorSettlement.capture(subject);
        ScheduledExecutorService owned = executor();
        AtomicInteger cleanups = new AtomicInteger();

        settlement.stopAndCleanup(subject::stop, DEADLINE, cleanups::incrementAndGet);

        assertAll(
            () -> assertThat(owned.isTerminated()).isTrue(),
            () -> assertThat(cleanups).hasValue(1)
        );
    }

    @Configuration
    @EnableConfigurationProperties(CouponWaitingProperties.class)
    @Import(CouponWaitingScheduler.class)
    static class Support {
    }

    @DisplayName("컨텍스트 시작이 실제 회차를 시작하고, 컨텍스트 종료가 실행기 스레드를 끝내며 그 뒤 새 회차가 없다. 미설정 컨텍스트는 bean 만 있다.")
    @Test
    void contextLifecycle() throws Exception {
        recordRounds(false);
        AtomicReference<ScheduledExecutorService> owned = new AtomicReference<>();
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Support.class)
            .withBean(CouponFacade.class, () -> facade);

        runner.withPropertyValues("commerce.coupon.waiting.campaign-id=" + CAMPAIGN, "commerce.coupon.waiting.scheduler-delay=50ms")
            .run(context -> {
                awaitRounds(2);
                owned.set((ScheduledExecutorService) ReflectionTestUtils.getField(context.getBean(CouponWaitingScheduler.class), "executor"));
            });
        int afterClose = rounds.size();
        Thread.sleep(300);

        assertAll(
            () -> assertThat(owned.get()).isNotNull(),
            () -> assertThat(owned.get().isTerminated()).as("컨텍스트 종료로 소유 스레드 종료").isTrue(),
            () -> assertThat(rounds).as("종료 뒤 새 회차 없음").hasSize(afterClose)
        );
        runner.run(context -> assertAll(
            () -> assertThat(context).hasNotFailed().hasSingleBean(CouponWaitingScheduler.class),
            () -> assertThat(ReflectionTestUtils.getField(context.getBean(CouponWaitingScheduler.class), "executor")).isNull()
        ));
    }
}
