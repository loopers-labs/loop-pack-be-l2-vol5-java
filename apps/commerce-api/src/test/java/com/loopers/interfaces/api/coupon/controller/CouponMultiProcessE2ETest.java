package com.loopers.interfaces.api.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.service.PrepareCouponCampaignService;
import com.loopers.couponprocess.CouponProcessApplication;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real sockets, two distinct JVM processes, one database and one isolated Redis server. */
@IntegrationTest
@Testcontainers
@TestPropertySource(properties = {"datasource.mysql-jpa.main.username=root", "datasource.mysql-jpa.main.password=test",
    "scheduler.like-count.enabled=false", "order-events.relay.enabled=false"})
class CouponMultiProcessE2ETest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        properties.add("datasource.redis.master.host", REDIS::getHost);
        properties.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        properties.add("datasource.redis.replicas[0].host", REDIS::getHost);
        properties.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private PrepareCouponCampaignService preparation;
    @Autowired private UserRepository users;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("mySqlMainDataSource") private HikariDataSource dataSource;
    @TempDir private Path temporary;

    @AfterEach
    void tearDown() { cleanUp.truncateAllTables(); }

    @Test
    void twoJvmInstancesShareDatabaseButHaveIndependentThreePermitLimits() throws Exception {
        for (long id = 1; id <= 8; id++) {
            users.save(User.create(id));
        }
        Instant now = Instant.now();
        long campaign = preparation.execute(new CouponCampaignCommand.Create("Independent process test", 10,
            "1000 KRW", 1000L, now.minusSeconds(3600), now.plusSeconds(3600))).campaignId();
        preparation.execute(new CouponCampaignCommand.Activation(campaign, true));
        var executor = Executors.newFixedThreadPool(6);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
             Child first = startChild("A", client);
             Child second = startChild("B", client)) {
            assertThat(first.process.pid()).isNotEqualTo(second.process.pid());
            assertThat(first.process.pid()).isNotEqualTo(ProcessHandle.current().pid());
            assertThat(first.status().path("open").asBoolean()).isFalse();
            assertThat(second.status().path("open").asBoolean()).isFalse();
            first.openAfterExplicitInitialVerification();
            second.openAfterExplicitInitialVerification();
            assertThat(first.status().path("bootId").asText()).isNotEqualTo(second.status().path("bootId").asText());
            first.helper("arm", Map.of("userIds", List.of(2, 3, 4)));
            second.helper("arm", Map.of("userIds", List.of(5, 6, 7)));
            List<Callable<HttpResponse<String>>> requests = List.of(
                () -> first.issue(campaign, 2), () -> first.issue(campaign, 3), () -> first.issue(campaign, 4),
                () -> second.issue(campaign, 5), () -> second.issue(campaign, 6), () -> second.issue(campaign, 7));
            List<Future<HttpResponse<String>>> pending = requests.stream().map(executor::submit).toList();
            await(() -> first.running() == 3 && second.running() == 3, Duration.ofSeconds(10),
                "both child JVMs must hold their own three real permits");
            assertThat(first.status().path("available").asInt()).isZero();
            assertThat(second.status().path("available").asInt()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons WHERE campaign_id = ?", Long.class, campaign)).isZero();
            HttpResponse<String> overflow = first.issue(campaign, 8);
            assertThat(overflow.statusCode()).isEqualTo(429);
            assertThat(mapper.readTree(overflow.body()).path("meta").path("errorCode").asText())
                .isEqualTo("COUPON_CAPACITY_EXCEEDED");
            first.helper("release", Map.of("count", 1));
            await(() -> first.available() == 1, Duration.ofSeconds(10), "first JVM returns exactly one permit after commit");
            assertThat(first.issue(campaign, 8).statusCode()).isEqualTo(201);
            assertThat(second.running()).isEqualTo(3);
            assertThat(second.available()).isZero();
            assertThat(first.post("/api/v1/admin/coupon-admission/close", 1, Map.of("reason", "test close A")).statusCode())
                .isEqualTo(200);
            assertThat(first.status().path("open").asBoolean()).isFalse();
            assertThat(second.status().path("open").asBoolean()).isTrue();
            first.helper("release", Map.of("count", 2));
            second.helper("release", Map.of("count", 3));
            for (Future<HttpResponse<String>> request : pending) {
                assertThat(request.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            }
            assertThat(first.available()).isEqualTo(3);
            assertThat(second.available()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons WHERE campaign_id = ?", Long.class, campaign))
                .isEqualTo(7L);
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaigns WHERE id = ?", Integer.class, campaign))
                .isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_slots WHERE campaign_id = ? AND state = 'ISSUED'",
                Long.class, campaign)).isEqualTo(7L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Child startChild(String name, HttpClient client) throws Exception {
        Path ready = temporary.resolve(name + ".ready");
        Path log = temporary.resolve(name + ".log");
        Path arguments = temporary.resolve(name + ".args");
        List<String> options = new ArrayList<>(List.of("-Xmx256m", "-Dfile.encoding=COMPAT", "-cp", classpath(),
            CouponProcessApplication.class.getName(), "--spring.profiles.active=test,coupon-process-child",
            "--server.address=127.0.0.1", "--server.port=0", "--management.server.port=-1",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.sql.init.mode=never", "--scheduler.like-count.enabled=false", "--order-events.relay.enabled=false",
            "--spring.kafka.listener.auto-startup=false", "--spring.kafka.admin.auto-create=false",
            "--spring.kafka.bootstrap-servers=127.0.0.1:1",
            "--datasource.mysql-jpa.main.jdbc-url=" + dataSource.getJdbcUrl(),
            "--datasource.mysql-jpa.main.username=" + dataSource.getUsername(),
            "--datasource.mysql-jpa.main.password=" + dataSource.getPassword(),
            "--datasource.mysql-jpa.main.maximum-pool-size=8", "--datasource.mysql-jpa.main.minimum-idle=2",
            "--datasource.redis.master.host=" + REDIS.getHost(), "--datasource.redis.master.port=" + REDIS.getMappedPort(6379),
            "--datasource.redis.replicas[0].host=" + REDIS.getHost(),
            "--datasource.redis.replicas[0].port=" + REDIS.getMappedPort(6379),
            "--coupon.admission.max-concurrency=3", "--coupon.process.ready-file=" + ready.toAbsolutePath(),
            "--logging.level.root=WARN", "--spring.jpa.show-sql=false"));
        // Java's launcher reads argument files in the native platform encoding, before JVM initialization.
        Charset nativeEncoding = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
        Files.write(arguments, options.stream().map(CouponMultiProcessE2ETest::quoteArgument).toList(), nativeEncoding);
        String executable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "@" + arguments.toAbsolutePath())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Child child = new Child(process, log, client);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(Files.exists(ready)).withFailMessage("Child %s failed to start. %s", name, child.diagnostics()).isTrue();
            child.port = Integer.parseInt(Files.readString(ready).trim());
            System.out.println("coupon test child=" + name + " pid=" + process.pid() + " port=" + child.port);
            return child;
        } catch (Throwable failure) {
            child.close();
            throw failure;
        } finally {
            // Credentials passed only to the owned child process must not remain in test artifacts.
            Files.deleteIfExists(arguments);
        }
    }

    private static String classpath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            entries.add(Path.of(entry).toAbsolutePath().toString());
        }
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toAbsolutePath().toString());
                    }
                }
            }
        }
        entries.add(Path.of(CouponProcessApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath().toString());
        return entries.stream().map(entry -> entry.replace('\\', '/')).collect(Collectors.joining(File.pathSeparator));
    }

    private static String quoteArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }

    private static void await(BooleanSupplier condition, Duration timeout, String description) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private final class Child implements AutoCloseable {
        private final Process process;
        private final Path log;
        private final HttpClient client;
        private int port;

        private Child(Process process, Path log, HttpClient client) {
            this.process = process;
            this.log = log;
            this.client = client;
        }

        private HttpResponse<String> post(String path, long user, Object body) throws Exception {
            return post(path, user, body, Duration.ofSeconds(15));
        }

        private HttpResponse<String> post(String path, long user, Object body, Duration timeout) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(timeout).header("X-USER-ID", Long.toString(user));
            if (body == null) {
                request.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> issue(long campaign, long user) throws Exception {
            // Longer than the child's 30-second barrier deadline, including TX and counter completion.
            return post("/api/v1/coupon-campaigns/" + campaign + "/issues", user, null, Duration.ofSeconds(45));
        }

        private JsonNode status() {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/coupon-admission"))
                    .timeout(Duration.ofSeconds(3)).header("X-USER-ID", "1").GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).withFailMessage("Child status HTTP %s: %s. %s", response.statusCode(),
                    response.body(), diagnostics()).isEqualTo(200);
                return mapper.readTree(response.body()).path("data");
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot read child state. " + diagnostics(), failure);
            }
        }

        private long running() { return status().path("running").asLong(); }
        private int available() { return status().path("available").asInt(); }

        private void openAfterExplicitInitialVerification() throws Exception {
            String boot = status().path("bootId").asText();
            HttpResponse<String> verified = post("/api/v1/admin/coupon-admission/verify-restart", 1,
                Map.of("expectedBootId", boot, "previousProcessStopped", true, "auditReference", "owned-test-no-prior-process",
                    "initialDeployment", true, "previousSessions", List.of()));
            assertThat(verified.statusCode()).withFailMessage("Verify HTTP %s: %s. %s", verified.statusCode(), verified.body(), diagnostics())
                .isEqualTo(200);
            assertThat(post("/api/v1/admin/coupon-admission/open", 1, Map.of("expectedBootId", boot)).statusCode()).isEqualTo(200);
        }

        private void helper(String operation, Object request) throws Exception {
            HttpResponse<String> response = post("/__test/coupon-barrier/" + operation, 1, request);
            assertThat(response.statusCode()).withFailMessage("Helper HTTP %s: %s. %s", response.statusCode(), response.body(), diagnostics())
                .isEqualTo(200);
        }

        private String diagnostics() {
            try {
                if (!Files.exists(log)) {
                    return "";
                }
                String output = decodeLog(Files.readAllBytes(log));
                String safe = output.codePoints().filter(code -> code == 9 || code == 10 || code == 13
                    || (code >= 0x20 && code <= 0xD7FF) || (code >= 0xE000 && code <= 0xFFFD)
                    || (code >= 0x10000 && code <= 0x10FFFF))
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
                int start = Math.max(0, safe.length() - 12000);
                if (start > 0 && Character.isLowSurrogate(safe.charAt(start))) {
                    start--;
                }
                return safe.substring(start);
            } catch (Exception failure) {
                return "Child diagnostics unavailable: " + failure.getClass().getSimpleName();
            }
        }

        private String decodeLog(byte[] bytes) throws CharacterCodingException {
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
            }
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
            }
            // Windows console encoders can emit BOM-less UTF-16; detect alternating NULs before UTF-8 decoding.
            int pairs = Math.min(bytes.length / 2, 256);
            int evenZero = 0;
            int oddZero = 0;
            for (int index = 0; index < pairs; index++) {
                evenZero += bytes[index * 2] == 0 ? 1 : 0;
                oddZero += bytes[index * 2 + 1] == 0 ? 1 : 0;
            }
            if (pairs >= 4 && oddZero * 2 >= pairs && evenZero * 8 < pairs) {
                return new String(bytes, StandardCharsets.UTF_16LE);
            }
            if (pairs >= 4 && evenZero * 2 >= pairs && oddZero * 8 < pairs) {
                return new String(bytes, StandardCharsets.UTF_16BE);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException nativeEncodedLog) {
                return Charset.forName(System.getProperty("native.encoding", "UTF-8"))
                    .newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            }
        }

        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive() && port != 0) {
                    try {
                        helper("release", Map.of("count", 100));
                    } catch (Exception | AssertionError ignored) {
                        // Child exit is still guaranteed when its HTTP endpoint is unavailable.
                    }
                }
            } finally {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("owned child JVM terminated").isTrue();
                }
            }
        }
    }
}
