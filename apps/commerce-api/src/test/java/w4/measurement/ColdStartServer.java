package w4.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.infrastructure.product.cache.ProductListCacheProperties;
import com.loopers.support.RequestSqlProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 새 JVM의 첫 실제 HTTP를 예열 없이 관찰하는 테스트 전용 자식 프로세스다. */
public final class ColdStartServer {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final RequestSqlProbe SQL = new RequestSqlProbe(REQUEST_ID::get);
    private static final Object REQUEST_MONITOR = new Object();
    private static final List<Request> REQUESTS = new ArrayList<>();
    private static final List<String> COUNTER_NAMES = List.of("lookup.hit", "lookup.miss", "lookup.invalid",
        "lookup.error", "store.success", "store.error");
    private static long totalRequests;
    private static int inFlight;

    private ColdStartServer() {
    }

    public static void main(String[] args) throws Exception {
        long startedAt = System.nanoTime();
        String configuredDirectory = System.getProperty("w4.startup.outputDir");
        if (configuredDirectory == null || !Path.of(configuredDirectory).isAbsolute()) {
            throw new IllegalArgumentException("w4.startup.outputDir must be an absolute directory");
        }
        Path outputDirectory = Path.of(configuredDirectory);
        Files.createDirectories(outputDirectory);
        AtomicLong readyAt = new AtomicLong();
        SpringApplication application = new SpringApplication(CommerceApiApplication.class,
            MeasurementConfiguration.class);
        application.addListeners((ApplicationListener<ApplicationReadyEvent>) event ->
            readyAt.compareAndSet(0, System.nanoTime()));
        try (ConfigurableApplicationContext context = application.run(args)) {
            if (!(context instanceof WebServerApplicationContext web) || readyAt.get() == 0) {
                throw new IllegalStateException("Web server must complete ApplicationReadyEvent before measurement");
            }
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            Map<String, Double> previousCounters = counters(registry);
            SQL.start();
            writeReady(outputDirectory, web.getWebServer().getPort(), readyAt.get() - startedAt,
                context.getBean(ProductListCacheProperties.class));
            try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String command;
                while ((command = commands.readLine()) != null) {
                    if (command.equals("EXIT")) {
                        break;
                    }
                    String[] parts = command.strip().split("\\s+");
                    if (parts.length != 2 || !parts[0].equals("CAPTURE") || !parts[1].matches("[1-9][0-9]*")) {
                        throw new IllegalArgumentException("Expected CAPTURE <positive integer> or EXIT");
                    }
                    RequestBatch batch = drainCompletedRequests();
                    List<RequestSqlProbe.Event> events = SQL.stop();
                    Map<String, Double> currentCounters = counters(registry);
                    Map<String, Double> delta = new LinkedHashMap<>();
                    for (String name : COUNTER_NAMES) {
                        delta.put(name, currentCounters.get(name) - previousCounters.get(name));
                    }
                    Capture capture = new Capture(ProcessHandle.current().pid(), batch.totalRequests(),
                        batch.requests(), events, delta);
                    previousCounters = currentCounters;
                    SQL.start();
                    writeCapture(outputDirectory.resolve("capture-" + parts[1] + ".json"), capture);
                }
            } finally {
                SQL.stop();
            }
        }
    }

    private static Map<String, Double> counters(MeterRegistry registry) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String name : COUNTER_NAMES) {
            String[] tags = name.split("\\.");
            Counter counter = registry.find("commerce.product.list.cache")
                .tags("operation", tags[0], "outcome", tags[1]).counter();
            values.put(name, counter == null ? 0.0 : counter.count());
        }
        return values;
    }

    private static void writeReady(Path directory, int port, long readyNanos, ProductListCacheProperties cache)
        throws IOException {
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("readyNanosFromMain", Long.toString(readyNanos));
        properties.setProperty("javaVersion", System.getProperty("java.version"));
        properties.setProperty("cacheEnabled", Boolean.toString(cache.enabled()));
        properties.setProperty("namespace", cache.namespace());
        properties.setProperty("ttlSeconds", Long.toString(cache.ttl().toSeconds()));
        Path temporary = Files.createTempFile(directory, "ready-", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                properties.store(stream, "ApplicationReadyEvent completed; no measurement HTTP has been sent");
            }
            Files.move(temporary, directory.resolve("ready.properties"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeCapture(Path destination, Capture capture) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "capture-", ".tmp");
        try {
            // HTTP 응답이 끝난 뒤 main 스레드에서만 직렬화한다. 운영 ObjectMapper를 미리 호출하지 않는다.
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(stream, capture);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static RequestBatch drainCompletedRequests() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        synchronized (REQUEST_MONITOR) {
            while (inFlight != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("HTTP filter has not completed before capture");
                }
                REQUEST_MONITOR.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            RequestBatch batch = new RequestBatch(totalRequests, List.copyOf(REQUESTS));
            REQUESTS.clear();
            return batch;
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class MeasurementConfiguration {
        @Bean
        static BeanPostProcessor coldStartJdbcObservation() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("mySqlMainDataSource")) {
                        return bean;
                    }
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        return invocation.getMethod().getName().equals("getConnection") && result instanceof Connection connection
                            ? SQL.wrap(connection) : result;
                    });
                    return proxy.getProxy();
                }
            };
        }

        @Bean
        FilterRegistrationBean<Filter> coldStartHttpObservation() {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                long sequence;
                synchronized (REQUEST_MONITOR) {
                    sequence = ++totalRequests;
                    inFlight++;
                }
                String suppliedId = httpRequest.getHeader("X-W4-SAMPLE-ID");
                String id = suppliedId == null || suppliedId.isBlank() ? "unlabelled-" + sequence : suppliedId;
                String query = httpRequest.getQueryString();
                String path = httpRequest.getRequestURI() + (query == null ? "" : "?" + query);
                REQUEST_ID.set(id);
                long startedAt = System.nanoTime();
                try {
                    chain.doFilter(request, response);
                } finally {
                    long elapsed = System.nanoTime() - startedAt;
                    REQUEST_ID.remove();
                    synchronized (REQUEST_MONITOR) {
                        REQUESTS.add(new Request(sequence, id, path, httpResponse.getStatus(), elapsed));
                        inFlight--;
                        REQUEST_MONITOR.notifyAll();
                    }
                }
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    public record Request(long sequence, String requestId, String path, int status, long elapsedNanos) {
    }

    private record RequestBatch(long totalRequests, List<Request> requests) {
    }

    public record Capture(long pid, long totalRequests, List<Request> requests,
                          List<RequestSqlProbe.Event> sql, Map<String, Double> counters) {
    }
}
