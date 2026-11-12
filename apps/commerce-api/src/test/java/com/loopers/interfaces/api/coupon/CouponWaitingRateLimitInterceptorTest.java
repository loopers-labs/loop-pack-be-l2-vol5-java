package com.loopers.interfaces.api.coupon;

import com.loopers.config.CouponRateLimitProperties;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 Resilience4j RateLimiter 를 쓰는 interceptor 를 직접 만들어 한도·소진·재허용·공유·대상 handler 를 본다(설계 §12.3, §11.4 RL1·RL2).
 * 소진 검사는 긴 주기를 쓰고 interceptor 생성 직전부터 관찰 종료까지 단조 시계로 잰 시간이 그 주기 안인지 확인한다.
 * 실제 MVC 선행성·resolver 전 거절은 CouponWaitingRateLimitE2ETest 가 본다. 제품에 시계·hook 을 두지 않고 limiter 설정만 test 에서 읽는다.
 */
@DisplayName("대기 등록 limiter 는 인스턴스 하나를 공유하고, 한도를 넘으면 기다리지 않고 429 이며, 다음 주기에 다시 허가한다.")
class CouponWaitingRateLimitInterceptorTest {

    private static final Duration LONG_PERIOD = Duration.ofHours(1);
    private static final Duration DEADLINE = Duration.ofSeconds(5);

    private static HandlerMethod handler(Object controller, String name, Class<?>... types) throws Exception {
        return new HandlerMethod(controller, controller.getClass().getMethod(name, types));
    }

    private static HandlerMethod registerWaiting() throws Exception {
        return handler(new CouponV1Controller(null), "registerWaiting", Long.class, Long.class);
    }

    private static boolean post(CouponWaitingRateLimitInterceptor interceptor, Object handler) {
        return interceptor.preHandle(new MockHttpServletRequest("POST", "/api/v1/coupon-campaigns/1/waiting"),
            new MockHttpServletResponse(), handler);
    }

    private static RateLimiterConfig config(CouponWaitingRateLimitInterceptor interceptor) {
        return ((RateLimiter) ReflectionTestUtils.getField(interceptor, "limiter")).getRateLimiterConfig();
    }

    private static void assertRateLimited(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreException.class,
            e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.COUPON_RATE_LIMITED));
    }

    @DisplayName("기본값은 1초당 10개·대기 0 이고, 시작 설정으로 바꾼 값을 그대로 쓴다.")
    @Test
    void configFromProperties() {
        RateLimiterConfig defaults = config(new CouponWaitingRateLimitInterceptor(new CouponRateLimitProperties(10, Duration.ofSeconds(1))));
        RateLimiterConfig changed = config(new CouponWaitingRateLimitInterceptor(new CouponRateLimitProperties(3, Duration.ofMillis(500))));

        assertAll(
            () -> assertThat(defaults.getLimitForPeriod()).isEqualTo(10),
            () -> assertThat(defaults.getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(defaults.getTimeoutDuration()).isEqualTo(Duration.ZERO),
            () -> assertThat(changed.getLimitForPeriod()).isEqualTo(3),
            () -> assertThat(changed.getLimitRefreshPeriod()).isEqualTo(Duration.ofMillis(500)),
            () -> assertThat(changed.getTimeoutDuration()).isEqualTo(Duration.ZERO)
        );
    }

    @DisplayName("긴 주기 안에서 한도만큼 허가한 뒤 다음 요청은 기다리지 않고 429 다. 대기 등록 외 handler 는 허가를 쓰지 않고 통과한다.")
    @Test
    void exhaustsWithoutWaiting() throws Exception {
        long begin = System.nanoTime();
        CouponWaitingRateLimitInterceptor interceptor = new CouponWaitingRateLimitInterceptor(new CouponRateLimitProperties(5, LONG_PERIOD));
        HandlerMethod register = registerWaiting();
        HandlerMethod issue = handler(new CouponV1Controller(null), "issue", Long.class, Long.class, String.class);
        HandlerMethod status = handler(new CouponAdmissionController(null), "status");

        for (int i = 0; i < 5; i++) {
            assertThat(post(interceptor, issue)).as("발급은 허가를 쓰지 않는다").isTrue();
            assertThat(post(interceptor, register)).isTrue();
        }
        long rejectStart = System.nanoTime();
        assertRateLimited(() -> post(interceptor, register));
        Duration rejectTook = Duration.ofNanos(System.nanoTime() - rejectStart);
        boolean getPasses = interceptor.preHandle(new MockHttpServletRequest("GET", "/api/v1/coupon-campaigns/1/waiting"),
            new MockHttpServletResponse(), handler(new CouponV1Controller(null), "getWaiting", Long.class, Long.class));
        Duration observed = Duration.ofNanos(System.nanoTime() - begin);

        assertAll(
            () -> assertThat(rejectTook).as("다음 주기를 기다리지 않는다").isLessThan(DEADLINE),
            () -> assertThat(getPasses).isTrue(),
            () -> assertThat(post(interceptor, issue)).isTrue(),
            () -> assertThat(post(interceptor, status)).isTrue(),
            () -> assertThat(post(interceptor, new Object())).as("HandlerMethod 아님").isTrue(),
            () -> assertThat(observed).as("관찰 전체가 한 주기 안").isLessThan(LONG_PERIOD)
        );
    }

    @DisplayName("대상은 메서드 이름이 아니라 표시로 고른다. 표시 없는 같은 이름 handler 는 허가를 쓰지 않는다.")
    @Test
    void selectsByMarkerNotByName() throws Exception {
        CouponWaitingRateLimitInterceptor interceptor = new CouponWaitingRateLimitInterceptor(new CouponRateLimitProperties(1, LONG_PERIOD));
        HandlerMethod unmarked = handler(new UnmarkedController(), "registerWaiting", Long.class, Long.class);

        boolean unmarkedPasses = post(interceptor, unmarked) && post(interceptor, unmarked);
        HandlerMethod marked = registerWaiting();
        boolean firstMarked = post(interceptor, marked);

        assertAll(
            () -> assertThat(marked.hasMethodAnnotation(CouponWaitingRateLimited.class)).isTrue(),
            () -> assertThat(unmarkedPasses).isTrue(),
            () -> assertThat(firstMarked).as("표시 없는 handler 가 허가를 쓰지 않았다").isTrue()
        );
        assertRateLimited(() -> post(interceptor, marked));
    }

    /** 이름만 같은 표시 없는 handler. */
    public static class UnmarkedController {
        public void registerWaiting(Long userId, Long campaignId) {
        }
    }

    @DisplayName("동시 32건이 한도 10 을 넘지 않는다(사용자·캠페인 구분 없이 하나를 공유).")
    @Test
    void sharedUnderConcurrency() throws Exception {
        long begin = System.nanoTime();
        CouponWaitingRateLimitInterceptor interceptor = new CouponWaitingRateLimitInterceptor(new CouponRateLimitProperties(10, LONG_PERIOD));
        HandlerMethod register = registerWaiting();
        int contenders = 32;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("시작 신호 대기 초과");
                    }
                    try {
                        return post(interceptor, register);
                    } catch (CoreException e) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            go.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)).as("worker 종료").isTrue();
        }
        long allowed = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(0, TimeUnit.MILLISECONDS)) {
                allowed++;
            }
        }
        long permitted = allowed;
        assertAll(
            () -> assertThat(permitted).isEqualTo(10),
            () -> assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(LONG_PERIOD)
        );
    }

    @DisplayName("짧은 주기에서 소진·거절을 확인한 뒤 유한 기한 안에 다시 허가한다.")
    @Test
    void refillsNextPeriod() throws Exception {
        CouponWaitingRateLimitInterceptor interceptor = new CouponWaitingRateLimitInterceptor(new CouponRateLimitProperties(2, Duration.ofMillis(300)));
        HandlerMethod register = registerWaiting();

        boolean rejected = false;
        for (int i = 0; i < 10 && !rejected; i++) {
            try {
                post(interceptor, register);
            } catch (CoreException e) {
                assertThat(e.getErrorType()).isEqualTo(ErrorType.COUPON_RATE_LIMITED);
                rejected = true;
            }
        }
        assertThat(rejected).as("한도 2 를 넘는 연속 요청에서 거절이 관찰돼야 한다").isTrue();

        long until = System.nanoTime() + DEADLINE.toNanos();
        boolean allowedAgain = false;
        while (!allowedAgain && System.nanoTime() < until) {
            try {
                allowedAgain = post(interceptor, register);
            } catch (CoreException e) {
                Thread.sleep(20);
            }
        }
        assertThat(allowedAgain).as("다음 주기 재허가").isTrue();
    }

    @Configuration
    @EnableConfigurationProperties(CouponRateLimitProperties.class)
    @Import(CouponWaitingRateLimitInterceptor.class)
    static class Support {
    }

    @DisplayName("설정 생략은 기동해 bean 하나를 만들고, Resilience4j 가 표현할 수 없는 갱신 주기는 interceptor 생성에서 기동 실패다.")
    @Test
    void unrepresentablePeriodFailsStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Support.class);

        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(CouponWaitingRateLimitInterceptor.class));
        runner.withPropertyValues("commerce.coupon.rate-limit.refresh-period=106752d")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().isInstanceOfAny(ArithmeticException.class, IllegalArgumentException.class));
    }
}
