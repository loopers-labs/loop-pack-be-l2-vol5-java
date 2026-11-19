package com.loopers.ranking;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Failure injection stays outside the production Job and its identifying parameters. */
final class RankingJobTestControl implements BeanPostProcessor {
    enum Failure { NONE, BEFORE_NINE, BEFORE_COMMIT, STOP_AFTER_CHUNK, BLOCK_NINE, FREEZE_METADATA }

    volatile Failure failure = Failure.NONE;
    volatile boolean repeatWrites;
    final List<Long> processed = new CopyOnWriteArrayList<>();
    final List<ProbeConnectionObservation.Event> beforeFailure = new CopyOnWriteArrayList<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final ProbeConnectionObservation observation;

    RankingJobTestControl(ProbeConnectionObservation observation) {
        this.observation = observation;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (name.equals("scopedTarget.weeklyRankingFreezeTasklet") && bean instanceof Tasklet delegate) {
            return (Tasklet) (contribution, context) -> {
                var result = delegate.execute(contribution, context);
                if (failure == Failure.FREEZE_METADATA) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("C04 freeze committed before Step metadata commit");
                        }
                    });
                }
                return result;
            };
        }
        if (name.equals("scopedTarget.weeklyRankingProcessor") && bean instanceof ItemProcessor<?, ?> delegate) {
            return (ItemProcessor<Long, Object>) id -> {
                processed.add(id);
                if (id == 9L && failure == Failure.BEFORE_NINE) {
                    throw new IllegalStateException("C04 before product 9");
                }
                if (id == 9L && failure == Failure.BLOCK_NINE) {
                    entered.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("C04 concurrent launch timed out");
                    }
                }
                return ((ItemProcessor<Long, Object>) delegate).process(id);
            };
        }
        if (name.equals("scopedTarget.weeklyRankingWriter") && bean instanceof ItemWriter<?> delegate) {
            return (ItemWriter<Object>) items -> {
                ((ItemWriter<Object>) delegate).write(items);
                if (repeatWrites) {
                    ((ItemWriter<Object>) delegate).write(items);
                }
                var step = StepSynchronizationManager.getContext().getStepExecution();
                if (failure == Failure.STOP_AFTER_CHUNK) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            step.setTerminateOnly();
                        }
                    });
                }
                if (failure == Failure.BEFORE_COMMIT && processed.contains(9L)) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            beforeFailure.addAll(observation.events());
                            throw new IllegalStateException("C04 after stage and checkpoint SQL");
                        }
                    });
                }
            };
        }
        return bean;
    }
}
