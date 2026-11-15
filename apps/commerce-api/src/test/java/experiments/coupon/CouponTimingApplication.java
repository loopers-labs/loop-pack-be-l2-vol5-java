package experiments.coupon;

import com.loopers.CommerceApiApplication;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** 선택한 테스트 클래스만 별도 classpath에 복사해 기동한다. 일반 앱·기능 테스트에는 등록되지 않는다. */
@Configuration(proxyBeanMethods = false)
public class CouponTimingApplication {
    private static final JdbcTimingProbe PROBE = new JdbcTimingProbe();
    private static final ConcurrentLinkedQueue<JdbcTimingProbe.RequestTiming> ROWS = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {CommerceApiApplication.class, CouponTimingApplication.class}, args);
    }

    @Bean
    static BeanPostProcessor timedDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof HikariDataSource)) return bean;
                ProxyFactory factory = new ProxyFactory(bean);
                factory.setProxyTargetClass(true);
                factory.addAdvice((MethodInterceptor) call -> {
                    if (!call.getMethod().getName().equals("getConnection")) return call.proceed();
                    return PROBE.acquire(() -> {
                        try { return (Connection) call.proceed(); }
                        catch (SQLException | RuntimeException failure) { throw failure; }
                        catch (Throwable failure) { throw new SQLException("Diagnostic acquisition failed", failure); }
                    });
                });
                return factory.getProxy();
            }
        };
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> requestTimings() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
                String uri = request.getRequestURI();
                String kind = uri.matches("/api/v1/coupon-campaigns/[0-9]+/issues") ? "issue"
                    : uri.equals("/api/v1/products") ? "product" : null;
                if (kind == null) { chain.doFilter(request, response); return; }
                PROBE.begin(kind, request.getHeader("X-Experiment-Request-ID"));
                try { chain.doFilter(request, response); }
                finally { ROWS.add(PROBE.end(response.getStatus())); }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }

    @RestController
    public static class TimingExport {
        @GetMapping("/__experiment/coupon-timings")
        public List<JdbcTimingProbe.RequestTiming> rows() { return List.copyOf(ROWS); }
    }
}
