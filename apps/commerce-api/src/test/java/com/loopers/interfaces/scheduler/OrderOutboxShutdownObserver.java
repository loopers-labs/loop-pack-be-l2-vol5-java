package com.loopers.interfaces.scheduler;

import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.config.OrderOutboxKafkaConfig;
import com.loopers.config.OrderOutboxRelayConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 실제 context.close 의 종료 순서를 관찰하는 테스트 전용 초기화기. phase +1·-50·-150 의 lifecycle 과 destroy 시점 bean 이
 * 그 순간의 실제 전용 pool·gate·전용 producer factory·relay thread 상태를 기록한다. 제품 bean 을 바꾸거나 감싸지 않는다.
 */
public final class OrderOutboxShutdownObserver implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** 관찰 시점과 그때의 실제 상태. */
    public record Snapshot(String point, boolean poolShutdown, boolean poolTerminated, boolean poolLifecycleRunning,
                           boolean gateStopping, boolean gateIdle, boolean producerFactoryRunning, long relayThreadsAlive) {
    }

    private final List<Snapshot> snapshots = new CopyOnWriteArrayList<>();
    private ConfigurableListableBeanFactory beans;
    private ThreadPoolTaskScheduler pool;
    private OrderOutboxExecutionGate gate;
    private DefaultKafkaProducerFactory<?, ?> producer;

    public List<Snapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    public Snapshot at(String point) {
        return snapshots.stream().filter(snapshot -> snapshot.point().equals(point)).findFirst()
            .orElseThrow(() -> new AssertionError("관찰 시점이 기록되지 않았습니다: " + point + " " + snapshots));
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        beans = context.getBeanFactory();
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();
        register(registry, "shutdownObserverPhasePlus1", 1);
        register(registry, "shutdownObserverPhaseMinus50", -50);
        register(registry, "shutdownObserverPhaseMinus150", -150);
        registry.registerBeanDefinition("shutdownObserverDestroy",
            new RootBeanDefinition(DestroyPoint.class, () -> new DestroyPoint(this)));
    }

    private void register(BeanDefinitionRegistry registry, String name, int phase) {
        registry.registerBeanDefinition(name, new RootBeanDefinition(PhasePoint.class, () -> new PhasePoint(this, phase)));
    }

    /** 기동 중(lifecycle start)에 실제 bean 참조를 잡아 둔다. 파괴 단계에서 getBean 을 다시 부르지 않기 위함이다. */
    synchronized void resolve() {
        if (pool == null) {
            pool = (ThreadPoolTaskScheduler) beans.getBean(OrderOutboxRelayConfig.RELAY_TASK_SCHEDULER);
            gate = beans.getBean(OrderOutboxExecutionGate.class);
            producer = (DefaultKafkaProducerFactory<?, ?>) beans.getBean(OrderOutboxKafkaConfig.ORDER_OUTBOX_PRODUCER_FACTORY);
        }
    }

    void record(String point) {
        boolean idle;
        try {
            idle = gate.awaitIdle(Duration.ZERO);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            idle = false;
        }
        snapshots.add(new Snapshot(point, pool.getScheduledExecutor().isShutdown(), pool.getScheduledExecutor().isTerminated(),
            pool.isRunning(), gate.isStopping(), idle, producer.isRunning(), relayThreadsAlive()));
    }

    public static long relayThreadsAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("order-outbox-relay-")).count();
    }

    /** 지정 phase 에서 정지될 때 기록한다. phase 가 클수록 먼저 정지된다. */
    static final class PhasePoint implements SmartLifecycle {

        private final OrderOutboxShutdownObserver observer;
        private final int phase;
        private volatile boolean running;

        PhasePoint(OrderOutboxShutdownObserver observer, int phase) {
            this.observer = observer;
            this.phase = phase;
        }

        @Override
        public void start() {
            observer.resolve();
            running = true;
        }

        @Override
        public void stop() {
            observer.record("phase" + phase);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }

    /** lifecycle 정지가 모두 끝난 뒤 bean 파괴 단계에서 기록한다. */
    static final class DestroyPoint implements DisposableBean {

        private final OrderOutboxShutdownObserver observer;

        DestroyPoint(OrderOutboxShutdownObserver observer) {
            this.observer = observer;
        }

        @Override
        public void destroy() {
            observer.record("destroy");
        }
    }
}
