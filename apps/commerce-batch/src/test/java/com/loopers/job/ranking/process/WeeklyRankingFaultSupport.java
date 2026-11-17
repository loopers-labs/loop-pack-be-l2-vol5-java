package com.loopers.job.ranking.process;

import com.loopers.batch.job.weeklyranking.step.WeeklyRankingScoreProcessor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 테스트 전용 실패 주입(랭킹 설계 11.5). 스캔 스테레오타입이 없어 @Import·자식 main 의 명시 등록으로만 쓰인다.
 * 실제 step-scoped Processor target 을 감싸 지정 상품의 process 직전에 한 번 예외를 던진다.
 * 자식 JVM 은 {@value #FAIL_BEFORE_PROPERTY} 가 있을 때만 arm 하며, 위임한 상품 ID 를 stdout 에 남겨 부모가 관찰한다.
 */
public class WeeklyRankingFaultSupport {

    public static final String FAIL_BEFORE_PROPERTY = "test.weekly-ranking.fail-before-product-id";

    @Bean
    public static FaultPlan weeklyRankingFaultPlan(Environment environment) {
        FaultPlan plan = new FaultPlan();
        Long failBefore = environment.getProperty(FAIL_BEFORE_PROPERTY, Long.class);
        if (failBefore != null) {
            plan.armBeforeProduct(failBefore);
        }
        return plan;
    }

    @Bean
    public static BeanPostProcessor weeklyRankingFaultInjector(ObjectProvider<FaultPlan> plan) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof WeeklyRankingScoreProcessor) || !beanName.startsWith("scopedTarget.")) {
                    return bean;
                }
                ProxyFactory factory = new ProxyFactory(bean);
                factory.setProxyTargetClass(true);
                factory.addAdvice((MethodInterceptor) invocation -> {
                    if (invocation.getMethod().getName().equals("process")) {
                        plan.getObject().beforeProcess((Long) invocation.getArguments()[0]);
                    }
                    return invocation.proceed();
                });
                return factory.getProxy();
            }
        };
    }

    /** 기본 비활성. arm 한 상품에서 한 번 실패한 뒤 소모된다. 실제 위임한 상품 ID 를 따로 기록한다. */
    public static final class FaultPlan {

        private Long failBeforeProductId;
        private final List<Long> delegated = new ArrayList<>();

        public synchronized void armBeforeProduct(long productId) {
            failBeforeProductId = productId;
        }

        public synchronized boolean isArmed() {
            return failBeforeProductId != null;
        }

        public synchronized List<Long> takeDelegated() {
            List<Long> result = List.copyOf(delegated);
            delegated.clear();
            return result;
        }

        synchronized void beforeProcess(Long productId) {
            if (productId.equals(failBeforeProductId)) {
                failBeforeProductId = null;
                System.out.println("WEEKLY_RANKING_FAULT beforeProduct=" + productId);
                throw new IllegalStateException("test fault before product " + productId);
            }
            System.out.println("WEEKLY_RANKING_PROCESS productId=" + productId);
            delegated.add(productId);
        }
    }
}
