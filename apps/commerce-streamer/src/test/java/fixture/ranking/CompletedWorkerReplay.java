package fixture.ranking;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.partition.support.DefaultStepExecutionAggregator;
import org.springframework.batch.core.partition.support.PartitionStep;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Test-only duplicate delivery from the second resident. Uses the application's real reader, writer and JobRepository. */
public final class CompletedWorkerReplay implements ApplicationContextInitializer<ConfigurableApplicationContext>, BeanPostProcessor {
    private final AtomicBoolean interruptOnce = new AtomicBoolean(true);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (Boolean.getBoolean("ranking.lab.replay-completed-worker")) {
            context.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(this));
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof AbstractStep worker && "scoreWeeklyRankingLocalProductPartition".equals(name)) {
            worker.setAllowStartIfComplete(true);
        }
        if (bean instanceof PartitionStep manager && "calculateWeeklyRankingOrderPartition".equals(name)) {
            var normal = new DefaultStepExecutionAggregator();
            manager.setStepExecutionAggregator((step, workers) -> {
                normal.aggregate(step, workers);
                if (!workers.isEmpty() && workers.stream().allMatch(worker -> worker.getStatus() == BatchStatus.COMPLETED)
                    && interruptOnce.getAndSet(false)) {
                    throw new IllegalStateException("Fixture interrupted manager after completed child commit");
                }
            });
        }
        return bean;
    }
}
