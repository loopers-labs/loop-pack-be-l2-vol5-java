package com.loopers.interfaces.scheduler;

import com.loopers.application.order.OrderConfirmedOutboxRelay;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.config.scheduling.OutboxRelaySchedulingConfig;
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
 * relay 스케줄러 연결 (7주차 설계 D22). 실제 tick 을 기다리지 않고 등록된 작업과 스케줄러 빈을 확인한다.
 */
@SpringBootTest
class OutboxRelaySchedulerTest {

    @MockitoSpyBean
    private OrderConfirmedOutboxRelay relay;

    @Autowired
    private OutboxRelayScheduler scheduler;
    @Autowired
    private OutboxRelayProperties properties;
    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;
    @Autowired
    private Map<String, TaskScheduler> taskSchedulers;
    @Autowired
    @Qualifier(OutboxRelaySchedulingConfig.OUTBOX_RELAY_TASK_SCHEDULER)
    private ThreadPoolTaskScheduler relayScheduler;

    @DisplayName("relay 의 run 은 fixedDelay(outbox.relay.fixed-delay) 작업으로 등록된다.")
    @Test
    void registersRelayRunAsFixedDelayTask() {
        // Spring 6.2 는 등록된 Runnable 을 OutcomeTrackingRunnable 로 감싼다. toString 은 "클래스명.메서드명" 으로 위임된다
        List<FixedDelayTask> relayTasks = scheduledProcessor.getScheduledTasks().stream()
            .map(ScheduledTask::getTask)
            .filter(FixedDelayTask.class::isInstance)
            .map(FixedDelayTask.class::cast)
            .filter(task -> task.getRunnable().toString().equals(OutboxRelayScheduler.class.getName() + ".run"))
            .toList();

        assertThat(relayTasks).hasSize(1);
        assertThat(relayTasks.get(0).getIntervalDuration()).isEqualTo(Duration.ofSeconds(10));
    }

    @DisplayName("기본 스케줄러(taskScheduler)와 relay 전용 단일 스레드 스케줄러가 따로 있다. relay 스케줄러는 스레드 1개, 이름 접두사 outbox-relay-.")
    @Test
    void hasDedicatedSingleThreadSchedulerBesidesDefault() {
        assertThat(taskSchedulers).containsKeys("taskScheduler", OutboxRelaySchedulingConfig.OUTBOX_RELAY_TASK_SCHEDULER);
        assertThat(taskSchedulers.get("taskScheduler")).isNotSameAs(relayScheduler);
        assertThat(relayScheduler.getPoolSize()).isEqualTo(1);
        assertThat(relayScheduler.getThreadNamePrefix()).isEqualTo("outbox-relay-");
    }

    @DisplayName("test 프로파일은 enabled=false 라 run 이 relay 를 호출하지 않는다 (테스트는 runOnce 를 직접 호출한다).")
    @Test
    void doesNotRunRelay_whenDisabled() {
        assertThat(properties.enabled()).isFalse();

        scheduler.run();

        verify(relay, never()).runOnce();
    }
}
