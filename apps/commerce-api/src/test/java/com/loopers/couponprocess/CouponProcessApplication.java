package com.loopers.couponprocess;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.support.CouponExecution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test runtime only. The parent launches this class in two separate JVMs. */
public final class CouponProcessApplication {
    private CouponProcessApplication() {}

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(ChildConfiguration.class, args);
    }

    // Deliberately excludes root package/testcontainers: children use the parent's isolated DB/Redis.
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ConfigurationPropertiesScan("com.loopers")
    @ComponentScan(basePackages = {"com.loopers.application", "com.loopers.config", "com.loopers.infrastructure",
        "com.loopers.interfaces", "com.loopers.support", "com.loopers.domain"}, excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
    public static class ChildConfiguration {
        @Bean
        Barrier barrier() { return new Barrier(); }

        @Bean
        BarrierController barrierController(Barrier barrier) { return new BarrierController(barrier); }

        @Bean
        BeforeIssueBarrier beforeIssueBarrier(Barrier barrier) { return new BeforeIssueBarrier(barrier); }

        @Bean
        ApplicationListener<ApplicationReadyEvent> childReady() {
            return event -> {
                try {
                    int port = ((WebServerApplicationContext) event.getApplicationContext()).getWebServer().getPort();
                    Path ready = Path.of(event.getApplicationContext().getEnvironment()
                        .getRequiredProperty("coupon.process.ready-file"));
                    Files.writeString(ready, Integer.toString(port));
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("Cannot publish child readiness", failure);
                }
            };
        }
    }

    static final class Barrier {
        private volatile Set<Long> blockedUsers = Set.of();
        private final Semaphore permits = new Semaphore(0);

        void arm(Set<Long> users) {
            permits.drainPermits();
            blockedUsers = Set.copyOf(users);
        }

        void await(long userId) throws InterruptedException {
            if (blockedUsers.contains(userId) && !permits.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test-only coupon barrier deadline exceeded");
            }
        }

        void release(int count) {
            if (count < 1 || count > 100) {
                throw new IllegalArgumentException("Invalid test-only release count");
            }
            permits.release(count);
        }
    }

    @Aspect
    static final class BeforeIssueBarrier {
        private final Barrier barrier;

        BeforeIssueBarrier(Barrier barrier) { this.barrier = barrier; }

        @Around("execution(* com.loopers.application.coupon.service.IssueCouponService.execute(..)) && args(command, execution)")
        public Object waitBeforeTransaction(ProceedingJoinPoint invocation, CouponIssueCommand command,
                                             CouponExecution execution) throws Throwable {
            barrier.await(command.userId());
            return invocation.proceed();
        }
    }

    @RestController
    @Profile("coupon-process-child")
    static final class BarrierController {
        private final Barrier barrier;

        BarrierController(Barrier barrier) { this.barrier = barrier; }

        public record Arm(Set<Long> userIds) {}
        public record Release(int count) {}

        @PostMapping("/__test/coupon-barrier/arm")
        void arm(@RequestBody Arm request) { barrier.arm(request.userIds()); }

        @PostMapping("/__test/coupon-barrier/release")
        void release(@RequestBody Release request) { barrier.release(request.count()); }
    }
}
