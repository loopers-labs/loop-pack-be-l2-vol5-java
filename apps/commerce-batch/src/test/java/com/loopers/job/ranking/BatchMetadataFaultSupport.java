package com.loopers.job.ranking;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JobRepository 테스트 대역(랭킹 설계 11.3·11.5). 지정 Step 의 StepExecution 저장을 한 번 실패시킨다.
 * IN_CHUNK 는 업무 쓰기와 같은 chunk TX 안의 저장, FINAL 은 업무 commit 뒤 Step 최종 저장이다.
 */
public class BatchMetadataFaultSupport {

    public enum Point { IN_CHUNK, FINAL }

    @Bean
    public static Plan batchMetadataFaultPlan() {
        return new Plan();
    }

    @Bean
    public static BeanPostProcessor batchMetadataFaultInjector(ObjectProvider<Plan> plan) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof JobRepository)) {
                    return bean;
                }
                ProxyFactory factory = new ProxyFactory(bean);
                factory.addInterface(JobRepository.class);
                factory.addAdvice((MethodInterceptor) invocation -> {
                    if (invocation.getMethod().getName().equals("update")
                        && invocation.getArguments()[0] instanceof StepExecution step) {
                        plan.getObject().beforeStepUpdate(step);
                    }
                    return invocation.proceed();
                });
                return factory.getProxy();
            }
        };
    }

    public static final class Plan {

        private String stepName;
        private Point point;

        public synchronized void arm(String stepName, Point point) {
            this.stepName = stepName;
            this.point = point;
        }

        public synchronized boolean isArmed() {
            return stepName != null;
        }

        synchronized void beforeStepUpdate(StepExecution step) {
            if (stepName == null || !stepName.equals(step.getStepName())) {
                return;
            }
            boolean matches = point == Point.IN_CHUNK
                ? TransactionSynchronizationManager.isActualTransactionActive() && step.getEndTime() == null
                : step.getEndTime() != null;
            if (matches) {
                stepName = null;
                throw new IllegalStateException("test metadata fault " + point + " at " + step.getStepName());
            }
        }
    }
}
