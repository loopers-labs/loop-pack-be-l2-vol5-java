package com.loopers.config.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.apache.catalina.AccessLog;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("experiment")
public class ExperimentConfiguration {
    @Bean
    static BeanPostProcessor experimentDataSource(org.springframework.beans.factory.ObjectProvider<ExperimentOriginMetrics> origins) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                Class<?> targetClass = AopUtils.getTargetClass(bean);
                String className = targetClass.getSimpleName();
                boolean timedComponent = targetClass.getPackageName().startsWith("com.loopers.")
                    && (className.endsWith("RepositoryAdapter") || className.equals("ProductUseCase")
                        || className.equals("ProductV1Controller"));
                if (timedComponent) {
                    ProxyFactory component = new ProxyFactory(bean);
                    component.setProxyTargetClass(true);
                    component.addAdvice((MethodInterceptor) invocation -> {
                        Object[] args = invocation.getArguments();
                        boolean latestFirst = className.equals("ProductUseCase") && invocation.getMethod().getName().equals("findBasicPage")
                            && args[0] == null && (args[1] == null || args[1] == com.loopers.product.domain.ProductSort.LATEST)
                            && Integer.valueOf(0).equals(args[2]) && Integer.valueOf(20).equals(args[3]);
                        ExperimentOriginMetrics.OriginalRead read = invocation::proceed;
                        boolean stockRead = className.equals("ProductUseCase") && invocation.getMethod().getName().equals("findStockStatuses");
                        if ((latestFirst || stockRead) && org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                            instanceof org.springframework.web.context.request.ServletRequestAttributes request) {
                            read = () -> stockRead
                                ? origins.getObject().recordStock(request.getRequest().getHeader("X-Run-ID"), invocation::proceed)
                                : origins.getObject().record(request.getRequest().getHeader("X-Run-ID"), invocation::proceed);
                        }
                        ExperimentTrace trace = ExperimentTrace.current();
                        if (trace == null) return read.get();
                        try (var ignored = trace.scope(className + "." + invocation.getMethod().getName())) {
                            return read.get();
                        }
                    });
                    return component.getProxy();
                }
                if (!(bean instanceof HikariDataSource)) return bean;
                ProxyFactory factory = new ProxyFactory(bean);
                factory.setProxyTargetClass(true);
                factory.addAdvice((MethodInterceptor) invocation -> {
                    if (!invocation.getMethod().getName().equals("getConnection")) return invocation.proceed();
                    ExperimentTrace trace = ExperimentTrace.current();
                    long start = System.nanoTime();
                    try (var ignored = trace == null ? null : trace.scope("jdbc.acquire")) {
                        Connection connection = (Connection) invocation.proceed();
                        return trace == null ? connection : ExperimentTrace.wrap(connection);
                    } finally {
                        if (trace != null) trace.connectionWaitMs += (System.nanoTime() - start) / 1_000_000.0;
                    }
                });
                return factory.getProxy();
            }
        };
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> experimentAccessLog(MeterRegistry registry,
        @Value("${experiment.trace.sample-rate:1.0}") double sampleRate) {
        AtomicInteger active = registry.gauge("experiment.active.requests", new AtomicInteger());
        return factory -> factory.addContextValves(new TraceValve(active, sampleRate, () -> ThreadLocalRandom.current().nextDouble()));
    }

    /** Tomcat calls AccessLog.log after response completion, including client disconnects. */
    static final class TraceValve extends ValveBase implements AccessLog {
        private static final Logger LOG = LoggerFactory.getLogger("EXPERIMENT_TRACE");
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String ATTRIBUTE = "experiment.trace";
        private final AtomicInteger active;
        private boolean attributesEnabled;
        private final double sampleRate;
        private final DoubleSupplier random;

        TraceValve(AtomicInteger active, double sampleRate, DoubleSupplier random) {
            super(true);
            if (!Double.isFinite(sampleRate) || sampleRate < 0 || sampleRate > 1) {
                throw new IllegalArgumentException("experiment.trace.sample-rate must be between 0 and 1");
            }
            this.active = active;
            this.sampleRate = sampleRate;
            this.random = random;
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            if (!validId(request.getHeader("X-Run-ID")) || !validId(request.getHeader("X-Request-ID"))
                || !(request.getRequestURI().equals("/api/v1/products") || purchaseRequest(request))) {
                getNext().invoke(request, response);
                return;
            }
            active.incrementAndGet();
            if (!purchaseRequest(request) && random.getAsDouble() >= sampleRate) {
                try {
                    getNext().invoke(request, response);
                } finally {
                    active.decrementAndGet();
                }
                return;
            }
            ExperimentTrace trace = new ExperimentTrace();
            request.setAttribute(ATTRIBUTE, trace);
            ExperimentTrace.begin(trace);
            try (var ignored = trace.scope("request.pipeline")) {
                try {
                    getNext().invoke(request, response);
                } finally {
                    trace.finishResponse();
                }
            } finally {
                ExperimentTrace.clear();
                active.decrementAndGet();
            }
        }

        private static boolean purchaseRequest(Request request) {
            return "POST".equals(request.getMethod()) && request.getRequestURI().matches("/api/v1/orders(?:/[0-9]+/confirm)?");
        }

        static boolean validId(String value) {
            return value != null && value.matches("[A-Za-z0-9._-]{1,120}");
        }

        @Override
        public void log(Request request, Response response, long time) {
            if (!(request.getAttribute(ATTRIBUTE) instanceof ExperimentTrace trace)) return;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("sampleRate", purchaseRequest(request) ? 1.0 : sampleRate);
            event.put("runId", request.getHeader("X-Run-ID"));
            event.put("requestId", request.getHeader("X-Request-ID"));
            event.put("finishedAt", Instant.now().toString());
            event.put("method", request.getMethod());
            event.put("path", request.getRequestURI());
            event.put("httpStatus", response.getStatus());
            // Tomcat 10.1 AccessLog receives nanoseconds from CoyoteAdapter.
            event.put("serverDurationMs", time / 1_000_000.0);
            event.put("connectionWaitMs", trace.connectionWaitMs);
            event.put("queries", trace.queries);
            event.put("stages", trace.timings());
            event.put("sqlTruncated", trace.truncated);
            event.put("async", request.isAsync());
            event.put("serverError", response.getCoyoteResponse().getErrorException() == null ? null
                : response.getCoyoteResponse().getErrorException().getClass().getSimpleName());
            try {
                LOG.info("EXPERIMENT_JSON {}", JSON.writeValueAsString(event));
            } catch (IOException error) {
                LOG.warn("Experiment trace serialization failed", error);
            }
        }

        @Override public void setRequestAttributesEnabled(boolean value) { attributesEnabled = value; }
        @Override public boolean getRequestAttributesEnabled() { return attributesEnabled; }
    }
}
