package com.loopers.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.support.RedisCacheMemoryProbe;
import com.loopers.support.RequestSqlProbe;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import w4.measurement.ColdStartServer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 운영 설정을 바꾸지 않고 실험 소유 서버 세 개의 TTL만 비교하는 장시간 명시 실행 실험이다. */
@Tag("ttl-measurement")
@Testcontainers
class ProductListCacheTtlMeasurementTest {
    private static final String REQUEST = "/api/v1/products?sort=likes_desc&page=0&size=20";
    private static final String LIKE_REQUEST = "/api/v1/products/100997/likes";
    private static final long SCENARIO_MILLIS = 195_000;
    private static final long READ_INTERVAL_MILLIS = 100;
    private static final List<Long> SPARSE_OFFSETS = List.of(0L, 10_000L, 50_000L, 60_000L, 100_000L,
        110_000L, 150_000L, 160_000L, 190_000L);
    private static final List<String> SCHEMA = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql", "007-product-brand-price-index.sql",
        "008-product-list-revision.sql");
    private static final Path OUTPUT = Path.of("build/reports/week4/product-list-cache-ttl.json");
    private static final Path TEST_SOURCE = Path.of("src/test/java/com/loopers/application/product/ProductListCacheTtlMeasurementTest.java");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w4_ttl_measurement")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
        .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
        .withExposedPorts(6379).withStartupTimeout(Duration.ofMinutes(2));

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final Map<String, String> sqlTemplates = new LinkedHashMap<>();
    private final List<RunningServer> servers = new ArrayList<>();
    private final List<Map<String, Object>> scenarios = new ArrayList<>();
    private final List<Map<String, Object>> preparations = new ArrayList<>();
    private Path runDirectory;
    private Path isolatedClasses;
    private StringRedisTemplate redis;
    private RedisCacheMemoryProbe memory;
    private JdbcTemplate jdbc;
    private HttpClient http;
    private JsonNode unchanged;
    private JsonNode liked;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void comparesTtlWithSteadySparseAndChangingReadsAndMeasuresOwnedRedisMemory() throws Exception {
        Files.createDirectories(OUTPUT.getParent());
        runDirectory = Files.createTempDirectory(OUTPUT.getParent(), "ttl-measurement-").toAbsolutePath();
        isolatedClasses = runDirectory.resolve("classes");
        copyClassFamily(ColdStartServer.class);
        copyClassFamily(RequestSqlProbe.class);
        prepareDatabase();
        unchanged = expectedPage(false);
        liked = expectedPage(true);
        assertThat(unchanged).isNotEqualTo(liked);
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                    .connectTimeout(Duration.ofSeconds(2)).build()).build()).build());
        Throwable primaryFailure = null;
        try {
            factory.afterPropertiesSet();
            factory.start();
            redis = new StringRedisTemplate(factory);
            memory = new RedisCacheMemoryProbe(redis);
            http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
            initializeReport();
            for (int ttl : List.of(5, 30, 60)) {
                servers.add(start(ttl));
            }
            assertThat(servers.stream().map(server -> server.process.pid())).doesNotHaveDuplicates();
            report.put("servers", servers.stream().map(server -> Map.of("pid", server.process.pid(),
                "ttlSeconds", server.ttlSeconds, "namespace", server.namespace, "ready", server.ready,
                "childLog", server.directory.resolve("server.log").toString())).toList());
            warmOutsideMeasurement();
            runScenario("STEADY");
            runScenario("SPARSE");
            runScenario("CHANGING");
            observeNaturalExpiry();
            assertDatabase(390);
            report.put("finalDatabase", databaseState());
            report.put("completed", true);
        } catch (Exception | AssertionError failure) {
            primaryFailure = failure;
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            Exception cleanupFailure = null;
            for (RunningServer server : servers.reversed()) {
                try {
                    server.close();
                } catch (Exception | AssertionError failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = new IllegalStateException("Owned child cleanup failed", failure);
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            }
            report.put("finishedAt", Instant.now().toString());
            report.put("childrenStopped", servers.stream().noneMatch(server -> server.process.isAlive()));
            if (cleanupFailure != null) {
                report.put("cleanupFailure", cleanupFailure.toString());
                report.put("completed", false);
            }
            try {
                save();
            } finally {
                try {
                    if (http != null) {
                        http.close();
                    }
                } finally {
                    factory.destroy();
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private void prepareDatabase() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(20);
        var scripts = new ResourceDatabasePopulator();
        SCHEMA.forEach(script -> scripts.addScript(new ClassPathResource("db/schema/" + script)));
        scripts.addScript(new ClassPathResource("fixtures/week4/product-query-baseline.sql"));
        scripts.execute(dataSource);
        assertThat(jdbc.queryForList("ANALYZE TABLE brand, product, `like`, `user`"))
            .allSatisfy(row -> assertThat(row.get("Msg_text")).isEqualTo("OK"));
        assertDatabase(0);
    }

    private void warmOutsideMeasurement() throws Exception {
        for (RunningServer server : servers) {
            List<String> outcomes = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                String id = "warm-" + server.ttlSeconds + "-" + index;
                Response response = exchange(server, "GET", REQUEST, id);
                JsonNode evidence = server.capture();
                assertResponse(response, unchanged);
                assertRequestEvidence(server, evidence, id, REQUEST);
                outcomes.add(classify(evidence));
                // 첫 연결 오류가 있더라도 기록한다. 측정 창에서는 같은 오류를 재시도로 숨기지 않는다.
            }
            preparations.add(Map.of("phase", "warmup", "ttlSeconds", server.ttlSeconds, "outcomes", outcomes));
        }
        assertDatabase(0);
        save();
    }

    private void runScenario(String name) throws Exception {
        write(false, "reset-" + name, null, 0, 0);
        assertThat(revision()).isZero();
        for (RunningServer server : servers) {
            var snapshot = memory.snapshot(server.namespace + ":");
            if (!snapshot.entries().isEmpty()) {
                redis.delete(snapshot.entries().stream().map(RedisCacheMemoryProbe.Entry::key).toList());
            }
            assertThat(memory.snapshot(server.namespace + ":").liveKeys()).isZero();
        }
        long beforeRevision = revision();
        Map<String, Object> scenario = new LinkedHashMap<>();
        List<ReadSample> reads = new ArrayList<>();
        List<Map<String, Object>> writes = new ArrayList<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        scenario.put("name", name);
        scenario.put("targetDurationMillis", SCENARIO_MILLIS);
        scenario.put("plannedReadIntervalMillis", name.equals("SPARSE") ? "explicit offsets" : READ_INTERVAL_MILLIS);
        scenario.put("plannedOffsetsMillis", name.equals("SPARSE") ? SPARSE_OFFSETS : "0..194900 step100");
        scenario.put("plannedWritesPerSecond", name.equals("CHANGING") ? 2 : 0);
        scenario.put("revisionBefore", beforeRevision);
        scenario.put("reads", reads);
        scenario.put("writes", writes);
        scenario.put("memorySnapshots", snapshots);
        scenarios.add(scenario);
        Map<Integer, Long> lastFilledRevision = new LinkedHashMap<>();
        long origin = System.nanoTime();
        long nextMemoryMillis = 0;
        long nextProgressMillis = 15_000;
        long currentRevision = beforeRevision;
        int writeCount = 0;
        boolean isLiked = false;
        int ticks = name.equals("SPARSE") ? SPARSE_OFFSETS.size() : (int) (SCENARIO_MILLIS / READ_INTERVAL_MILLIS);
        for (int tick = 0; tick < ticks; tick++) {
            long scheduledMillis = name.equals("SPARSE") ? SPARSE_OFFSETS.get(tick) : tick * READ_INTERVAL_MILLIS;
            // 드문 조회 사이에도 5초마다 메모리를 관찰하되 HTTP를 발생시키지는 않는다.
            while (nextMemoryMillis <= scheduledMillis) {
                waitUntil(origin + TimeUnit.MILLISECONDS.toNanos(nextMemoryMillis));
                snapshotAll(snapshots, origin, nextMemoryMillis, currentRevision);
                nextMemoryMillis += 5_000;
                if (elapsedMillis(origin) >= nextProgressMillis) {
                    progress(name, tick, ticks, origin);
                    nextProgressMillis += 15_000;
                }
            }
            waitUntil(origin + TimeUnit.MILLISECONDS.toNanos(scheduledMillis));
            if (name.equals("CHANGING") && tick % 5 == 0) {
                isLiked = !isLiked;
                write(isLiked, name + "-write-" + writeCount, writes, origin, scheduledMillis);
                writeCount++;
                currentRevision++;
                assertThat(revision()).as("완료된 실제 좋아요 변경마다 DB 버전 1 증가").isEqualTo(currentRevision);
            }
            JsonNode expected = isLiked ? liked : unchanged;
            String expectedSha = sha(mapper.writeValueAsBytes(canonical(expected)));
            for (int position = 0; position < servers.size(); position++) {
                RunningServer server = servers.get((tick + position) % servers.size());
                String id = name + "-" + tick + "-ttl" + server.ttlSeconds;
                report.put("currentRequest", Map.of("id", id, "ttlSeconds", server.ttlSeconds, "revision", currentRevision));
                long startedOffset = System.nanoTime() - origin;
                Response response = exchange(server, "GET", REQUEST, id);
                long captureStarted = System.nanoTime();
                JsonNode evidence = server.capture();
                long captureNanos = System.nanoTime() - captureStarted;
                String outcome = classify(evidence);
                String cause = "NONE";
                if (!outcome.equals("HIT")) {
                    Long previous = lastFilledRevision.get(server.ttlSeconds);
                    cause = previous == null ? "FIRST_FILL" : previous != currentRevision ? "VERSION_CHANGE" : "SAME_VERSION_EXPIRY";
                }
                ReadSample sample = new ReadSample(id, server.ttlSeconds, tick, position, scheduledMillis,
                    startedOffset / 1_000_000.0, Math.max(0, startedOffset / 1_000_000.0 - scheduledMillis),
                    response.elapsedNanos / 1_000_000.0, captureNanos / 1_000_000.0,
                    response.status, currentRevision, isLiked ? "liked" : "unchanged", outcome, cause,
                    sha(mapper.writeValueAsBytes(canonical(response.body.path("data")))), evidence.path("sql").size(),
                    aggregateCount(evidence), sqlExecuteNanos(evidence), templateIds(evidence), evidence.path("counters"));
                reads.add(sample);
                report.put("lastObservation", Map.of("id", id, "response", response.body, "evidence", evidence));
                assertResponse(response, expected);
                assertThat(sample.responseSha256()).isEqualTo(expectedSha);
                assertRequestEvidence(server, evidence, id, REQUEST);
                assertReadSql(evidence, outcome);
                if (outcome.equals("MISS_STORED")) {
                    lastFilledRevision.put(server.ttlSeconds, currentRevision);
                }
            }
            if (elapsedMillis(origin) >= nextProgressMillis) {
                progress(name, tick + 1, ticks, origin);
                nextProgressMillis += 15_000;
            }
        }
        while (nextMemoryMillis <= SCENARIO_MILLIS) {
            waitUntil(origin + TimeUnit.MILLISECONDS.toNanos(nextMemoryMillis));
            snapshotAll(snapshots, origin, nextMemoryMillis, currentRevision);
            nextMemoryMillis += 5_000;
        }
        waitUntil(origin + TimeUnit.MILLISECONDS.toNanos(SCENARIO_MILLIS));
        scenario.put("actualDurationMillis", elapsedMillis(origin));
        scenario.put("revisionAfter", revision());
        scenario.put("completedWrites", writeCount);
        scenario.put("summaries", summarize(reads));
        scenario.put("correctness", "Every full DTO equals the independent expected state after the last committed write; SQL and cache counters checked per request");
        assertThat(reads).hasSize(ticks * servers.size());
        assertThat(writeCount).isEqualTo(name.equals("CHANGING") ? 390 : 0);
        assertThat(revision()).isEqualTo(beforeRevision + writeCount);
        assertDatabase(beforeRevision + writeCount);
        scenario.put("completed", true);
        report.remove("lastObservation");
        report.remove("currentRequest");
        save();
    }

    private void progress(String scenario, int ticks, int total, long origin) throws IOException {
        System.out.println("W4 TTL " + scenario + " ticks=" + ticks + "/" + total + " elapsed=" + elapsedMillis(origin) + "ms");
        save();
    }

    private void write(boolean targetLiked, String id, List<Map<String, Object>> writes, long origin, long scheduledMillis) throws Exception {
        RunningServer writer = servers.getFirst();
        long offset = origin == 0 ? 0 : System.nanoTime() - origin;
        Response response = exchange(writer, targetLiked ? "POST" : "DELETE", LIKE_REQUEST, id);
        JsonNode evidence = writer.capture();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("id", id);
        sample.put("liked", targetLiked);
        sample.put("scheduledOffsetMillis", scheduledMillis);
        sample.put("actualOffsetMillis", offset / 1_000_000.0);
        sample.put("httpMillis", response.elapsedNanos / 1_000_000.0);
        sample.put("status", response.status);
        sample.put("body", response.body);
        sample.put("sqlTemplateIds", templateIds(evidence));
        sample.put("sqlCount", evidence.path("sql").size());
        sample.put("sqlExecuteNanos", sqlExecuteNanos(evidence));
        sample.put("counters", evidence.path("counters"));
        if (writes == null) {
            preparations.add(sample);
        } else {
            writes.add(sample);
        }
        report.put("lastWriteObservation", Map.of("sample", sample, "evidence", evidence));
        assertThat(response.status).isEqualTo(200);
        assertThat(response.body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
        JsonNode data = response.body.path("data");
        assertThat(data.path("productId").asLong()).isEqualTo(100997);
        assertThat(data.path("liked").asBoolean()).isEqualTo(targetLiked);
        assertThat(data.path("likeCount").asInt()).isEqualTo(targetLiked ? 201 : 200);
        assertRequestEvidence(writer, evidence, id, LIKE_REQUEST);
        evidence.path("counters").elements().forEachRemaining(value -> assertThat(value.asDouble()).isZero());
        report.remove("lastWriteObservation");
    }

    private void snapshotAll(List<Map<String, Object>> target, long origin, long scheduledMillis, long currentRevision) throws Exception {
        for (RunningServer server : servers) {
            long offset = System.nanoTime() - origin;
            var snapshot = memory.snapshot(server.namespace + ":");
            long current = snapshot.entries().stream().filter(entry -> Long.valueOf(currentRevision).equals(entry.revision())).count();
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("ttlSeconds", server.ttlSeconds);
            observation.put("scheduledOffsetMillis", scheduledMillis);
            observation.put("actualOffsetMillis", offset / 1_000_000.0);
            observation.put("dbRevision", currentRevision);
            observation.put("currentVersionKeys", current);
            observation.put("oldVersionKeys", snapshot.liveKeys() - current);
            observation.put("snapshot", snapshot);
            target.add(observation);
            assertThat(snapshot.server().evictedKeys()).as("만료 miss를 eviction과 혼동하지 않도록 한다").isZero();
            assertThat(snapshot.entries()).allSatisfy(entry -> {
                assertThat(entry.key()).startsWith(server.namespace + ":");
                assertThat(entry.revision()).isNotNull().isLessThanOrEqualTo(currentRevision);
                assertThat(entry.ttlMillis()).isBetween(0L, server.ttlSeconds * 1000L);
            });
        }
        save();
    }

    private void observeNaturalExpiry() throws Exception {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        report.put("noTrafficExpirySnapshots", snapshots);
        report.put("expiryVerification", "All application HTTP stopped; read-only observation at 0/5/30/61 seconds; no key deletion or TTL refresh");
        long origin = System.nanoTime();
        for (long seconds : List.of(0L, 5L, 30L, 61L)) {
            waitUntil(origin + TimeUnit.SECONDS.toNanos(seconds));
            snapshotAll(snapshots, origin, seconds * 1000, revision());
            if (seconds == 61) {
                for (RunningServer server : servers) {
                    assertThat(memory.snapshot(server.namespace + ":").liveKeys()).as("자연 만료 후 실험 namespace 키 0").isZero();
                }
            }
            System.out.println("W4 TTL no-traffic expiry observed at " + seconds + "s");
        }
    }

    private Response exchange(RunningServer server, String method, String path, String id) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.ready.getProperty("port") + path))
            .timeout(Duration.ofSeconds(20)).header("X-W4-SAMPLE-ID", id);
        if (!method.equals("GET")) {
            builder.header("X-USER-ID", "alice");
        }
        HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        long started = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsed = System.nanoTime() - started;
        return new Response(elapsed, response.statusCode(), mapper.readTree(response.body()));
    }

    private void assertResponse(Response response, JsonNode expected) {
        assertThat(response.status).isEqualTo(200);
        assertThat(response.body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.body.path("data")).isEqualTo(expected);
    }

    private void assertRequestEvidence(RunningServer server, JsonNode evidence, String id, String path) {
        assertThat(evidence.path("totalRequests").asInt()).isEqualTo(++server.requests);
        assertThat(evidence.path("requests")).hasSize(1);
        JsonNode observed = evidence.path("requests").get(0);
        assertThat(observed.path("sequence").asInt()).isEqualTo(server.requests);
        assertThat(observed.path("requestId").asText()).isEqualTo(id);
        assertThat(observed.path("path").asText()).isEqualTo(path);
        assertThat(observed.path("status").asInt()).isEqualTo(200);
        for (JsonNode event : evidence.path("sql")) {
            assertThat(event.path("requestId").asText()).isEqualTo(id);
            assertThat(event.path("success").asBoolean()).isTrue();
        }
    }

    private void assertReadSql(JsonNode evidence, String outcome) {
        JsonNode counters = evidence.path("counters");
        assertThat(counters.path("lookup.invalid").asLong()).isZero();
        assertThat(counters.path("lookup.error").asLong()).as("실제 장애 표본은 기록 후 실패; 재시도하지 않음").isZero();
        assertThat(counters.path("store.error").asLong()).isZero();
        List<String> sql = new ArrayList<>();
        evidence.path("sql").forEach(event -> sql.add(event.path("sql").asText().toLowerCase(Locale.ROOT)));
        assertThat(sql).allSatisfy(query -> assertThat(query).startsWith("select "));
        if (outcome.equals("HIT")) {
            assertThat(sql).hasSize(1);
            assertThat(sql.getFirst()).contains("from product_list_revision");
            assertThat(counters.path("lookup.hit").asLong()).isEqualTo(1);
            assertThat(counters.path("lookup.miss").asLong()).isZero();
            assertThat(counters.path("store.success").asLong()).isZero();
        } else {
            assertThat(outcome).isEqualTo("MISS_STORED");
            assertThat(sql).hasSize(5);
            assertThat(counters.path("lookup.hit").asLong()).isZero();
            assertThat(counters.path("lookup.miss").asLong()).isEqualTo(2);
            assertThat(counters.path("store.success").asLong()).isEqualTo(1);
            assertThat(sql.stream().filter(query -> query.contains("from product_list_revision")).count()).isEqualTo(2);
            assertThat(sql.stream().filter(query -> query.startsWith("select count(") && query.contains("from product ")).count()).isEqualTo(1);
            assertThat(aggregateCount(evidence)).isEqualTo(1);
            assertThat(sql.stream().filter(query -> query.contains(" in (") && !query.contains("join `like`")).count()).isEqualTo(1);
        }
    }

    private String classify(JsonNode evidence) {
        JsonNode counters = evidence.path("counters");
        if (counters.path("lookup.error").asLong() > 0) {
            return "GET_ERROR_DB_FALLBACK";
        }
        if (counters.path("lookup.hit").asLong() > 0) {
            return "HIT";
        }
        return counters.path("store.error").asLong() > 0 ? "MISS_SET_ERROR" : "MISS_STORED";
    }

    private long aggregateCount(JsonNode evidence) {
        long count = 0;
        for (JsonNode event : evidence.path("sql")) {
            String sql = event.path("sql").asText().toLowerCase(Locale.ROOT);
            if (sql.contains("left join `like`") && sql.contains("group by") && sql.contains("limit")) {
                count++;
            }
        }
        return count;
    }

    private long sqlExecuteNanos(JsonNode evidence) {
        long nanos = 0;
        for (JsonNode event : evidence.path("sql")) {
            nanos += event.path("executeNanos").asLong();
        }
        return nanos;
    }

    private List<String> templateIds(JsonNode evidence) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode event : evidence.path("sql")) {
            String sql = event.path("sql").asText();
            String id = sha(sql.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
            sqlTemplates.putIfAbsent(id, sql);
            ids.add(id);
        }
        return ids;
    }

    private Map<String, Object> summarize(List<ReadSample> reads) {
        Map<String, Object> summaries = new LinkedHashMap<>();
        for (RunningServer server : servers) {
            List<ReadSample> group = reads.stream().filter(read -> read.ttlSeconds == server.ttlSeconds).toList();
            List<ReadSample> hits = group.stream().filter(read -> read.outcome.equals("HIT")).toList();
            List<ReadSample> misses = group.stream().filter(read -> !read.outcome.equals("HIT")).toList();
            Map<String, Long> causes = new LinkedHashMap<>();
            misses.forEach(read -> causes.merge(read.missCause, 1L, Long::sum));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("requests", group.size());
            summary.put("hits", hits.size());
            summary.put("misses", misses.size());
            summary.put("hitRatio", hits.size() / (double) group.size());
            summary.put("missCauses", causes);
            summary.put("httpMillis", distribution(group.stream().map(ReadSample::httpMillis).toList()));
            summary.put("hitHttpMillis", distribution(hits.stream().map(ReadSample::httpMillis).toList()));
            summary.put("missHttpMillis", distribution(misses.stream().map(ReadSample::httpMillis).toList()));
            summary.put("scheduleLagMillis", distribution(group.stream().map(ReadSample::scheduleLagMillis).toList()));
            summary.put("captureMillis", distribution(group.stream().map(ReadSample::captureMillis).toList()));
            summary.put("selectCount", group.stream().mapToLong(ReadSample::selectCount).sum());
            summary.put("aggregateCount", group.stream().mapToLong(ReadSample::aggregateCount).sum());
            summary.put("sqlExecuteMillis", group.stream().mapToLong(ReadSample::sqlExecuteNanos).sum() / 1_000_000.0);
            summary.put("quantileNote", "Nearest-rank p95; sparse has only nine samples and is not a latency SLO");
            summaries.put(Integer.toString(server.ttlSeconds), summary);
        }
        return summaries;
    }

    private Map<String, Object> distribution(List<Double> values) {
        if (values.isEmpty()) {
            return Map.of("count", 0);
        }
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return Map.of("count", n, "min", sorted.getFirst(), "p50", n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2 : sorted.get(n / 2),
            "p95", sorted.get((int) Math.ceil(n * .95) - 1), "max", sorted.getLast());
    }

    private void initializeReport() throws Exception {
        report.put("startedAt", Instant.now().toString());
        report.put("completed", false);
        report.put("request", REQUEST);
        report.put("ttlSeconds", List.of(5, 30, 60));
        report.put("productionSettingChanged", false);
        report.put("seed", Map.of("products", 10000, "visibleProducts", 9812, "likes", 46955));
        report.put("expectedPages", Map.of("unchanged", unchanged, "liked", liked));
        report.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("schema", SCHEMA);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path path : List.of(TEST_SOURCE, Path.of("src/test/java/com/loopers/support/RedisCacheMemoryProbe.java"),
            Path.of("src/test/java/w4/measurement/ColdStartServer.java"),
            Path.of("src/test/resources/fixtures/week4/product-query-baseline.sql"),
            Path.of("src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java"),
            Path.of("src/main/resources/application.yml"))) {
            hashes.put(path.toString(), sha(Files.readAllBytes(path)));
        }
        report.put("sourceSha256", hashes);
        report.put("conditions", "Three actual app JVMs share one owned MySQL/Redis; equal-byte distinct namespaces; only command-line TTL differs. "
            + "All writes complete before reads, each tick rotates TTL order, HTTP requests are sequential. "
            + "Scenario windows start empty after ten unmeasured warmup reads per JVM; no deletion/TTL refresh during a measurement window.");
        report.put("limitations", List.of("One local run per scenario, synthetic traffic and dataset; not a production optimum or concurrency/load capacity claim.",
            "All three TTLs share DB/Redis and OS resources; sequential order is rotated, not a randomized independent experiment.",
            "HTTP timing excludes parent assertions, child diagnostic capture, memory probes and JSON report writes; their scheduling lag is recorded.",
            "Actual offered rate can be lower than plan if sequential observer overhead overruns a tick; report actual duration and delays.",
            "JDBC execute timing excludes ResultSet consumption. Application/filter/JDBC observer overhead remains.",
            "Redis per-key MEMORY USAGE is allocation, STRLEN is payload, INFO memory is whole shared Redis process; RSS need not shrink after expiry.",
            "Memory snapshots use SCAN and are not atomic peaks. Expired keys may disappear while being observed.",
            "Hit ratio is per HTTP request; an ordinary miss has two Redis lookups because single-flight rechecks.",
            "Warmup errors are retained outside measurements. Measured errors are preserved and fail the experiment without retry.",
            "No application warmup feature, production TTL change, transaction/lock change, shared key deletion or shared volume deletion."));
        report.put("sqlTemplates", sqlTemplates);
        report.put("preparations", preparations);
        report.put("scenarios", scenarios);
        save();
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class);
    }

    private Map<String, Object> databaseState() {
        return Map.of("products", jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class),
            "likes", jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class), "revision", revision(),
            "aliceTargetLikes", jdbc.queryForObject("SELECT COUNT(*) FROM `like` WHERE user_id=1 AND product_id=100997", Long.class));
    }

    private void assertDatabase(long expectedRevision) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(46955);
        assertThat(revision()).isEqualTo(expectedRevision);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN brand b ON b.id=p.brand_id "
            + "WHERE p.deleted_at IS NULL AND b.deleted_at IS NULL", Long.class)).isEqualTo(9812);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like` WHERE user_id=1 AND product_id=100997", Long.class)).isZero();
    }

    private JsonNode expectedPage(boolean extraLike) {
        var indices = IntStream.rangeClosed(1, 10000).filter(index -> index % 100 != 0 && index % 113 != 0)
            .boxed().sorted(Comparator.<Integer>comparingInt(index -> likes(index, extraLike)).reversed()
                .thenComparing(Comparator.reverseOrder())).toList();
        ObjectNode page = mapper.createObjectNode();
        var items = page.putArray("items");
        indices.stream().limit(20).forEach(index -> {
            var item = items.addObject();
            item.put("productId", 100000 + index).put("name", "W4 product %05d".formatted(index))
                .put("price", 1000 + index * 37 % 1000 * 100)
                .put("stockQuantity", index % 17 == 0 ? 0 : 1 + index % 50).put("likeCount", likes(index, extraLike));
            int brand = 1001 + (index - 1) % 100;
            item.putObject("brand").put("brandId", brand).put("name", "W4 brand %03d".formatted(brand - 1000));
        });
        return page.put("page", 0).put("size", 20).put("totalElements", indices.size()).put("totalPages", 491);
    }

    private static int likes(int index, boolean extraLike) {
        return (index % 997 == 0 ? 200 : ((index - 1) / 100 + index) % 10) + (extraLike && index == 997 ? 1 : 0);
    }

    private void save() throws IOException {
        Files.writeString(OUTPUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // JSON 객체의 필드 순서는 의미가 없지만 배열의 순서는 정렬 계약이므로 유지한다.
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }

    private static double elapsedMillis(long origin) {
        return (System.nanoTime() - origin) / 1_000_000.0;
    }

    private static void waitUntil(long deadline) throws InterruptedException {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
    }

    private RunningServer start(int ttl) throws Exception {
        String namespace = "commerce:test:ttl%02d:v1".formatted(ttl);
        Path directory = Files.createDirectory(runDirectory.resolve("ttl-" + ttl));
        String productionClasspath = System.getProperty("w4.startup.main-classpath");
        assertThat(productionClasspath).isNotBlank();
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms128m", "-Xmx512m", "-Xshare:off", "-Duser.timezone=Asia/Seoul", "-Dw4.startup.outputDir=" + directory,
            "-cp", isolatedClasses + File.pathSeparator + productionClasspath, ColdStartServer.class.getName(),
            "--spring.profiles.active=test", "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.show-sql=false",
            "--server.port=0", "--management.server.port=0", "--spring.lifecycle.timeout-per-shutdown-phase=5s",
            "--datasource.mysql-jpa.main.jdbc-url=" + MYSQL.getJdbcUrl(),
            "--datasource.mysql-jpa.main.username=" + MYSQL.getUsername(),
            "--datasource.mysql-jpa.main.password=" + MYSQL.getPassword(),
            "--datasource.redis.database=0", "--datasource.redis.master.host=" + REDIS.getHost(),
            "--datasource.redis.master.port=" + REDIS.getFirstMappedPort(),
            "--datasource.redis.replicas[0].host=" + REDIS.getHost(),
            "--datasource.redis.replicas[0].port=" + REDIS.getFirstMappedPort(),
            "--commerce.product-list-cache.enabled=true", "--commerce.product-list-cache.namespace=" + namespace,
            "--commerce.product-list-cache.ttl=" + ttl + "s"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(directory.resolve("server.log").toFile()).start();
        RunningServer server = new RunningServer(process, directory, ttl, namespace);
        try {
            awaitFile(directory.resolve("ready.properties"), process, Duration.ofSeconds(45));
            try (var input = Files.newInputStream(directory.resolve("ready.properties"))) {
                server.ready.load(input);
            }
            assertThat(Long.parseLong(server.ready.getProperty("pid"))).isEqualTo(process.pid());
            assertThat(server.ready.getProperty("cacheEnabled")).isEqualTo("true");
            assertThat(server.ready.getProperty("namespace")).isEqualTo(namespace);
            assertThat(server.ready.getProperty("ttlSeconds")).isEqualTo(Integer.toString(ttl));
            return server;
        } catch (Exception | AssertionError failure) {
            try {
                server.close();
            } catch (Exception | AssertionError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void copyClassFamily(Class<?> type) throws Exception {
        Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve(type.getPackageName().replace('.', '/'));
        Path destination = isolatedClasses.resolve(type.getPackageName().replace('.', '/'));
        Files.createDirectories(destination);
        try (var entries = Files.list(source)) {
            for (Path entry : entries.filter(path -> path.getFileName().toString().equals(type.getSimpleName() + ".class")
                || path.getFileName().toString().startsWith(type.getSimpleName() + "$"))
                .filter(path -> path.toString().endsWith(".class")).toList()) {
                Files.copy(entry, destination.resolve(entry.getFileName()));
            }
        }
    }

    private static void awaitFile(Path path, Process process, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException("Child signal unavailable: " + path + "; alive=" + process.isAlive()
                + "; see " + path.getParent().resolve("server.log"));
        }
    }

    private record Response(long elapsedNanos, int status, JsonNode body) { }

    private record ReadSample(String id, int ttlSeconds, int tick, int position, long scheduledOffsetMillis,
                              double actualOffsetMillis, double scheduleLagMillis, double httpMillis, double captureMillis,
                              int status, long revision, String responseState, String outcome, String missCause,
                              String responseSha256, int selectCount, long aggregateCount, long sqlExecuteNanos,
                              List<String> sqlTemplateIds, JsonNode counters) { }

    private final class RunningServer implements AutoCloseable {
        private final Process process;
        private final Path directory;
        private final int ttlSeconds;
        private final String namespace;
        private final BufferedWriter input;
        private final Properties ready = new Properties();
        private int captures;
        private int requests;

        private RunningServer(Process process, Path directory, int ttlSeconds, String namespace) {
            this.process = process;
            this.directory = directory;
            this.ttlSeconds = ttlSeconds;
            this.namespace = namespace;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        private JsonNode capture() throws Exception {
            int number = ++captures;
            input.write("CAPTURE " + number + "\n");
            input.flush();
            Path signal = directory.resolve("capture-" + number + ".json");
            awaitFile(signal, process, Duration.ofSeconds(10));
            JsonNode evidence = mapper.readTree(Files.readString(signal));
            assertThat(evidence.path("pid").asLong()).isEqualTo(process.pid());
            return evidence;
        }

        @Override
        public void close() throws Exception {
            boolean[] interrupted = {Thread.interrupted()};
            IOException inputFailure = null;
            try {
                try {
                    if (process.isAlive()) {
                        input.write("EXIT\n");
                        input.flush();
                    }
                } catch (IOException failure) {
                    inputFailure = failure;
                } finally {
                    try {
                        input.close();
                    } catch (IOException failure) {
                        if (inputFailure == null) {
                            inputFailure = failure;
                        } else {
                            inputFailure.addSuppressed(failure);
                        }
                    }
                }
                if (!awaitTermination(Duration.ofSeconds(15), interrupted)) {
                    process.destroy();
                    if (!awaitTermination(Duration.ofSeconds(5), interrupted)) {
                        process.destroyForcibly();
                        assertThat(awaitTermination(Duration.ofSeconds(5), interrupted)).as("실험 소유 child JVM 종료").isTrue();
                    }
                }
                assertThat(process.isAlive()).isFalse();
                assertThat(process.exitValue()).as("실험 소유 child JVM 정상 종료").isZero();
                if (inputFailure != null) {
                    throw inputFailure;
                }
            } finally {
                if (interrupted[0]) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private boolean awaitTermination(Duration timeout, boolean[] interrupted) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted[0] = true;
                }
            }
            return true;
        }
    }
}
