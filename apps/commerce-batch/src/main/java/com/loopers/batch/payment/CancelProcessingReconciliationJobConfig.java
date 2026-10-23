package com.loopers.batch.payment;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = CancelProcessingReconciliationJobConfig.JOB_NAME)
public class CancelProcessingReconciliationJobConfig {
    public static final String JOB_NAME = "cancelProcessingReconciliationJob";
    private static final String STEP_NAME = "reconcileCancelProcessingStep";

    @Bean("reconcileCancelProcessingTasklet")
    public ReconcileCancelProcessingTasklet reconcileCancelProcessingTasklet(
        PaymentReconciliationRepository repository,
        PaymentLookupClient lookupClient,
        PaymentStateInterpreter interpreter
    ) {
        return new ReconcileCancelProcessingTasklet(repository, lookupClient, interpreter);
    }

    @Bean(JOB_NAME)
    public Job cancelProcessingReconciliationJob(JobRepository jobRepository, Step reconcileCancelProcessingStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(reconcileCancelProcessingStep)
            .build();
    }

    @JobScope
    @Bean(STEP_NAME)
    public Step reconcileCancelProcessingStep(JobRepository jobRepository, ReconcileCancelProcessingTasklet tasklet) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .tasklet(tasklet, new ResourcelessTransactionManager())
            .build();
    }
}
