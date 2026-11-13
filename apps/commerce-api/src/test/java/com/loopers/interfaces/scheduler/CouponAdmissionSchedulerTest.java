package com.loopers.interfaces.scheduler;

import com.loopers.application.coupon.CouponAdmissionFacade;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionSchedulingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 쿠폰 승격·probe 스케줄러 연결 (8주차 설계 §5-2 D2, §7-3, C11). 실제 tick 을 기다리지 않고 등록된 작업과 스케줄러 빈을 확인한다.
 */
@SpringBootTest
class CouponAdmissionSchedulerTest {

    @MockitoSpyBean
    private CouponAdmissionFacade facade;

    @Autowired
    private CouponAdmissionScheduler scheduler;
    @Autowired
    private CouponAdmissionProperties properties;
    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;
    @Autowired
    private Map<String, TaskScheduler> taskSchedulers;
    @Autowired
    @Qualifier(CouponAdmissionSchedulingConfig.COUPON_ADMISSION_TASK_SCHEDULER)
    private ThreadPoolTaskScheduler couponScheduler;

    private List<FixedDelayTask> tasksOf(String method) {
        return scheduledProcessor.getScheduledTasks().stream()
            .map(ScheduledTask::getTask)
            .filter(FixedDelayTask.class::isInstance)
            .map(FixedDelayTask.class::cast)
            .filter(task -> task.getRunnable().toString().equals(CouponAdmissionScheduler.class.getName() + "." + method))
            .toList();
    }

    @DisplayName("promote 는 fixedDelay 200ms, probe 는 fixedDelay 5초 작업으로 등록된다.")
    @Test
    void registersPromoteAndProbe() {
        List<FixedDelayTask> promote = tasksOf("promote");
        List<FixedDelayTask> probe = tasksOf("probe");

        assertThat(promote).hasSize(1);
        assertThat(promote.get(0).getIntervalDuration()).isEqualTo(Duration.ofMillis(200));
        assertThat(probe).hasSize(1);
        assertThat(probe.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(5));
    }

    @DisplayName("쿠폰 전용 스케줄러는 기본·relay 스케줄러와 별개이며 스레드 1개, 이름 접두사 coupon-admission- 이다.")
    @Test
    void hasDedicatedSingleThreadScheduler() {
        assertThat(taskSchedulers).containsKeys("taskScheduler", CouponAdmissionSchedulingConfig.COUPON_ADMISSION_TASK_SCHEDULER);
        assertThat(taskSchedulers.get("taskScheduler")).isNotSameAs(couponScheduler);
        assertThat(couponScheduler.getPoolSize()).isEqualTo(1);
        assertThat(couponScheduler.getThreadNamePrefix()).isEqualTo("coupon-admission-");
    }

    @DisplayName("test 프로파일은 enabled=false 라 promote·probe 가 Facade 를 호출하지 않는다 (테스트는 promoteOnce·probeOnce 를 직접 호출한다).")
    @Test
    void doesNothing_whenDisabled() {
        assertThat(properties.enabled()).isFalse();

        scheduler.promote();
        scheduler.probe();

        verify(facade, never()).promoteOnce();
        verify(facade, never()).probeOnce();
    }
}
