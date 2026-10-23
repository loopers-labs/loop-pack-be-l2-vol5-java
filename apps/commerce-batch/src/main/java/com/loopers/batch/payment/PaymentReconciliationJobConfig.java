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

import java.time.Clock;

@Configuration
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = PaymentReconciliationJobConfig.JOB_NAME)
public class PaymentReconciliationJobConfig {
    public static final String JOB_NAME = "paymentReconciliationJob";
    private static final String STEP_NAME = "compareRecentPaymentsStep";

    @Bean("paymentReconciliationClock")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(Clock.class)
    public Clock paymentReconciliationClock() {
        return Clock.systemDefaultZone();
    }

    @Bean("compareRecentPaymentsTasklet")
    public CompareRecentPaymentsTasklet compareRecentPaymentsTasklet(
        PaymentReconciliationRepository repository,
        PaymentLookupClient lookupClient,
        PaymentStateInterpreter interpreter,
        Clock paymentReconciliationClock
    ) {
        return new CompareRecentPaymentsTasklet(repository, lookupClient, interpreter, paymentReconciliationClock);
    }

    @Bean(JOB_NAME)
    public Job paymentReconciliationJob(JobRepository jobRepository, Step compareRecentPaymentsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(compareRecentPaymentsStep)
            .build();
    }

    @JobScope
    @Bean(STEP_NAME)
    public Step compareRecentPaymentsStep(JobRepository jobRepository, CompareRecentPaymentsTasklet tasklet) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .tasklet(tasklet, new ResourcelessTransactionManager())
            .build();
    }
}
