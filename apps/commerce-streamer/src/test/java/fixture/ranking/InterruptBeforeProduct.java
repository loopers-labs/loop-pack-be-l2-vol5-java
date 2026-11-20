package fixture.ranking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs only in the process fixture. Records which items reached the real processor in each execution. */
public final class InterruptBeforeProduct implements ApplicationContextInitializer<ConfigurableApplicationContext>, BeanPostProcessor {
    private Path observations;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String path = System.getProperty("ranking.lab.product-observations");
        if (path != null) {
            observations = Path.of(path);
            context.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(this));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!"scopedTarget.localProductPartitionProcessor".equals(name)) {
            return bean;
        }
        var processor = (ItemProcessor<Long, ?>) bean;
        return (ItemProcessor<Long, Object>) product -> {
            long execution = StepSynchronizationManager.getContext().getStepExecution().getJobExecutionId();
            Path log = observations.resolve("products-" + execution + ".log");
            if (product == 9 && Files.deleteIfExists(observations.resolve("interrupt-on-9"))) {
                Files.writeString(log, "before 9\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                throw new IllegalStateException("Fixture stopped before processing product 9");
            }
            Object result = processor.process(product);
            Files.writeString(log, product + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return result;
        };
    }
}
