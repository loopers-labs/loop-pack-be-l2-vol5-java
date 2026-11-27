package com.loopers.support.test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.loopers.interfaces.api.ordering.dto.PaymentCallbackRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.testcontainers.containers.MySQLContainer;

/** Commerce의 DB와 클래스패스를 공유하지 않는 실제 테스트 PG. */
public final class IsolatedTestPg implements AutoCloseable {
    private static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final String CALLBACK_PATH = "/api/v1/payments/callback";
    private static final ObjectReader CALLBACK_READER = new ObjectMapper()
        .readerFor(PaymentCallbackRequest.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MySQLContainer<?> database;
    private final ConcurrentMap<String, CompletableFuture<HeldCallback>> callbacksByOrder = new ConcurrentHashMap<>();
    private HttpServer callback;
    private Process process;
    private int port;

    private IsolatedTestPg() {
        database = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loopers_test_pg")
            .withInitScript("payment-timeout/002-pg-transactions.sql")
            .withStartupTimeout(Duration.ofSeconds(90))
            .withStartupAttempts(1);
    }

    public static IsolatedTestPg start(Path runDirectory) {
        String jar = Objects.requireNonNull(System.getProperty("payment.test-pg.jar"), "test-pg bootJar 경로가 필요합니다.");
        Path log = runDirectory.toAbsolutePath().resolve("pg.log");
        IsolatedTestPg pg = new IsolatedTestPg();
        try {
            Files.createDirectories(log.getParent());
            pg.database.start();
            pg.callback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            pg.callback.createContext(CALLBACK_PATH, pg::holdCallback);
            pg.callback.start();
            String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
                "-XX:ActiveProcessorCount=2", "-Xmx256m", "-Dfile.encoding=UTF-8", "-jar", jar,
                "--spring.profiles.active=payment-lab", "--server.address=127.0.0.1", "--server.port=0",
                "--pg.processing.enabled=true", "--pg.callback-url=" + pg.callbackUrl(),
                "--datasource.mysql-jpa.main.jdbc-url=" + pg.database.getJdbcUrl(),
                "--datasource.mysql-jpa.main.username=${ISOLATED_PG_USERNAME}",
                "--datasource.mysql-jpa.main.password=${ISOLATED_PG_PASSWORD}");
            command.environment().put("ISOLATED_PG_USERNAME", pg.database.getUsername());
            command.environment().put("ISOLATED_PG_PASSWORD", pg.database.getPassword());
            pg.process = command.directory(log.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            pg.port = pg.awaitPort(log);
            return pg;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try {
                pg.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("테스트 PG를 기동하지 못했습니다. 로그 " + log, failure);
        }
    }

    private int awaitPort(Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            var match = TOMCAT_PORT.matcher(output);
            if (output.contains("Started TestPgApplication") && match.find()) {
                return Integer.parseInt(match.group(1));
            }
            process.waitFor(200, TimeUnit.MILLISECONDS);
        }
        throw new IllegalStateException("테스트 PG의 HTTP 포트를 확인하지 못했습니다. 로그 " + log);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public String callbackUrl() {
        return "http://127.0.0.1:" + callback.getAddress().getPort() + CALLBACK_PATH;
    }

    private void holdCallback(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!CALLBACK_PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            HeldCallback notification;
            try {
                PaymentCallbackRequest request = CALLBACK_READER.readValue(exchange.getRequestBody());
                var validated = request.toCommand();
                notification = new HeldCallback(validated.providerOrderId(), validated.transactionKey(), validated.status().name());
            } catch (IOException | RuntimeException invalid) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            boolean firstDelivery = callbackFor(notification.providerOrderId()).complete(notification);
            exchange.sendResponseHeaders(firstDelivery ? 204 : 409, -1);
        }
    }

    private CompletableFuture<HeldCallback> callbackFor(String providerOrderId) {
        return callbacksByOrder.computeIfAbsent(providerOrderId, key -> new CompletableFuture<>());
    }

    public HeldCallback awaitCallback(String providerOrderId) {
        try {
            return callbackFor(providerOrderId).get(5, TimeUnit.SECONDS);
        } catch (TimeoutException missing) {
            throw new AssertionError("실습용 PG에서 주문 " + providerOrderId + "의 콜백을 5초 안에 받지 못했습니다.", missing);
        } catch (ExecutionException invalid) {
            throw new AssertionError("실습용 PG 콜백 수신에 실패했습니다.", invalid.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실습용 PG 콜백 대기가 중단되었습니다.", interrupted);
        }
    }

    public record HeldCallback(String providerOrderId, String transactionKey, String status) {}

    @Override
    public void close() {
        try {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("테스트 PG 프로세스를 종료하지 못했습니다. pid=" + process.pid());
                    }
                }
            }
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 PG 종료 대기가 중단됐습니다.", failure);
        } finally {
            try {
                if (callback != null) { callback.stop(0); }
            } finally {
                database.close();
            }
        }
    }
}
