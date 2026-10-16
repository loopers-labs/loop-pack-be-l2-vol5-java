package com.loopers.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.support.RequestSqlProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
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

/** 앱 JVM만 매번 새로 시작한다. 운영 워밍을 추가하지 않고 기존 캐시 잔존/부재를 비교한다. */
@Testcontainers
class ProductListColdStartMeasurementTest {
    private static final String REQUEST = "/api/v1/products?sort=likes_desc&page=0&size=20";
    private static final String NAMESPACE = "commerce:test:cold-start:v1";
    private static final String KEY = NAMESPACE + ":brand=all:sort=likes_desc:page=0:size=20:rev=0";
    private static final int PAIRS = 8;
    private static final List<String> SCHEMA = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql", "007-product-brand-price-index.sql",
        "008-product-list-revision.sql");
    private static final Path OUTPUT = Path.of("build/reports/week4/product-list-cold-start.json");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w4_cold_start")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
        .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
        .withExposedPorts(6379).withStartupTimeout(Duration.ofMinutes(2));

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final List<Map<String, Object>> preparations = new ArrayList<>();
    private Path runDirectory;
    private Path isolatedClasses;
    private StringRedisTemplate redis;
    private JsonNode expected;
    private HttpClient http;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void measuresFirstHttpInFreshProcessesWithRetainedAndEmptyRedis() throws Exception {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(20);
        var scripts = new ResourceDatabasePopulator();
        SCHEMA.forEach(script -> scripts.addScript(new ClassPathResource("db/schema/" + script)));
        scripts.addScript(new ClassPathResource("fixtures/week4/product-query-baseline.sql"));
        scripts.execute(dataSource);
        assertThat(jdbc.queryForList("ANALYZE TABLE brand, product, `like`, `user`"))
            .allSatisfy(row -> assertThat(row.get("Msg_text")).isEqualTo("OK"));
        assertDatabase(jdbc);
        expected = expectedPage();
        Files.createDirectories(OUTPUT.getParent());
        runDirectory = Files.createTempDirectory(OUTPUT.getParent(), "cold-start-").toAbsolutePath();
        isolatedClasses = runDirectory.resolve("classes");
        copyClassFamily(ColdStartServer.class);
        copyClassFamily(RequestSqlProbe.class);
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        Map<String, Object> report = report(jdbc);
        try {
            // 실제 이전 앱이 저장한 값을 남긴다. 측정 대상 JVM은 아직 시작하지 않는다.
            try (RunningServer producer = start("producer")) {
                prepareNextProcess(producer, "producer");
            }
            for (int pair = 1; pair <= PAIRS; pair++) {
                List<String> order = pair % 2 == 1 ? List.of("retained", "empty") : List.of("empty", "retained");
                for (String condition : order) {
                    measure(pair, condition);
                    save(report);
                }
            }
            assertThat(samples).hasSize(2 * PAIRS);
            assertThat(samples.stream().map(sample -> sample.get("pid"))).doesNotHaveDuplicates();
            assertDatabase(jdbc);
            report.put("summaries", summaries());
            report.put("completed", true);
        } catch (Exception | AssertionError failure) {
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            try {
                save(report);
            } finally {
                try {
                    http.close();
                } finally {
                    factory.destroy();
                }
            }
        }
    }

    private void measure(int pair, String condition) throws Exception {
        assertThat(ttl()).as("이전 실제 앱이 60초 TTL로 채운 값이 살아 있어야 한다").isBetween(40_000L, 60_000L);
        String retainedPayload = redis.opsForValue().get(KEY);
        assertThat(retainedPayload).isNotNull();
        if (condition.equals("empty")) {
            assertThat(redis.delete(KEY)).isTrue();
        }
        long beforeStartTtl = ttl();
        String label = "pair-" + pair + "-" + condition;
        try (RunningServer server = start(label)) {
            long beforeRequestTtl = ttl();
            assertThat(server.ready.getProperty("cacheEnabled")).isEqualTo("true");
            assertThat(server.ready.getProperty("namespace")).isEqualTo(NAMESPACE);
            assertThat(server.ready.getProperty("ttlSeconds")).isEqualTo("60");
            if (condition.equals("retained")) {
                assertThat(beforeRequestTtl).as("캐시 있음 표본이 실제 요청 전에 만료되면 조건 실패").isPositive();
                assertThat(redis.opsForValue().get(KEY)).isEqualTo(retainedPayload);
            } else {
                assertThat(beforeRequestTtl).as("기동만으로 캐시를 채우지 않아야 한다").isEqualTo(-2);
            }
            long readyToFirstNanos = System.nanoTime() - server.readyObservedNanos;
            var first = request(server, label + "-first");
            JsonNode firstEvidence = server.capture();
            String firstOutcome = assertEvidence(firstEvidence, label + "-first", 1, condition);
            long afterFirstTtl = ttl();
            var second = request(server, label + "-second");
            JsonNode secondEvidence = server.capture();
            String secondOutcome = assertEvidence(secondEvidence, label + "-second", 2, "subsequent");
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("pair", pair);
            sample.put("condition", condition);
            sample.put("pid", server.process.pid());
            sample.put("ready", server.ready);
            sample.put("launchToReadyObservedMillis", server.launchToReadyNanos / 1_000_000.0);
            sample.put("readyObservedToFirstSendMillis", readyToFirstNanos / 1_000_000.0);
            sample.put("ttlBeforeStartMillis", beforeStartTtl);
            sample.put("ttlBeforeRequestMillis", beforeRequestTtl);
            sample.put("ttlAfterFirstMillis", afterFirstTtl);
            sample.put("retainedPayloadSha256", sha(retainedPayload.getBytes(StandardCharsets.UTF_8)));
            sample.put("first", observation(first, firstEvidence, firstOutcome));
            sample.put("second", observation(second, secondEvidence, secondOutcome));
            sample.put("childLog", server.directory.resolve("server.log").toString());
            samples.add(sample);
            System.out.println("W4 cold-start " + label + " first=" + first.elapsedNanos / 1_000_000.0
                + "ms outcome=" + firstOutcome + " second=" + second.elapsedNanos / 1_000_000.0 + "ms");
            // 측정 후 준비: 다음 자식 JVM에 캐시가 남는 상황을 이전 앱의 실제 API로 만든다.
            prepareNextProcess(server, label);
        }
    }

    private Map<String, Object> observation(Response response, JsonNode evidence, String outcome) {
        return Map.of("httpMillis", response.elapsedNanos / 1_000_000.0, "status", response.status,
            "body", response.body, "outcome", outcome, "evidence", evidence);
    }

    private void prepareNextProcess(RunningServer server, String label) throws Exception {
        redis.delete(KEY);
        List<String> outcomes = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String id = label + "-prepare-" + attempt;
            request(server, id);
            JsonNode evidence = server.capture();
            outcomes.add(classify(evidence));
            if (ttl() > 50_000 && evidence.path("counters").path("store.success").asLong() == 1) {
                preparations.add(Map.of("predecessorPid", server.process.pid(), "label", label,
                    "outcomes", List.copyOf(outcomes), "ttlAfterStoreMillis", ttl(),
                    "note", "Outside measured requests; exact owned key removed, then real previous app repopulated it"));
                return;
            }
        }
        throw new IllegalStateException("측정 전 캐시 잔존 조건을 실제 이전 앱에서 준비하지 못했습니다: " + outcomes);
    }

    private Response request(RunningServer server, String requestId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.ready.getProperty("port") + REQUEST))
            .timeout(Duration.ofSeconds(20)).header("X-W4-SAMPLE-ID", requestId).GET().build();
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long elapsed = System.nanoTime() - start;
        JsonNode body = mapper.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(body.path("data")).isEqualTo(expected);
        return new Response(elapsed, response.statusCode(), body);
    }

    private String assertEvidence(JsonNode evidence, String requestId, int sequence, String condition) {
        assertThat(evidence.path("totalRequests").asInt()).isEqualTo(sequence);
        assertThat(evidence.path("requests")).hasSize(1);
        JsonNode observed = evidence.path("requests").get(0);
        assertThat(observed.path("sequence").asInt()).isEqualTo(sequence);
        assertThat(observed.path("requestId").asText()).isEqualTo(requestId);
        assertThat(observed.path("path").asText()).isEqualTo(REQUEST);
        assertThat(observed.path("status").asInt()).isEqualTo(200);
        for (JsonNode event : evidence.path("sql")) {
            assertThat(event.path("requestId").asText()).isEqualTo(requestId);
            assertThat(event.path("success").asBoolean()).isTrue();
            assertThat(event.path("sql").asText().toLowerCase(Locale.ROOT)).startsWith("select ");
        }
        String outcome = classify(evidence);
        var counters = evidence.path("counters");
        assertThat(counters.path("lookup.invalid").asLong()).isZero();
        List<String> sql = new ArrayList<>();
        evidence.path("sql").forEach(event -> sql.add(event.path("sql").asText().toLowerCase(Locale.ROOT)));
        if (outcome.equals("HIT")) {
            assertThat(evidence.path("sql")).hasSize(1);
            assertThat(counters.path("lookup.hit").asLong()).isEqualTo(1);
            assertThat(counters.path("lookup.miss").asLong()).isZero();
            assertThat(counters.path("lookup.error").asLong()).isZero();
            assertThat(counters.path("store.success").asLong()).isZero();
            assertThat(counters.path("store.error").asLong()).isZero();
            assertThat(sql.getFirst()).contains("from product_list_revision");
        } else if (outcome.equals("GET_ERROR_DB_FALLBACK")) {
            assertThat(evidence.path("sql")).hasSize(4);
            assertThat(counters.path("lookup.error").asLong()).isEqualTo(1);
            assertThat(counters.path("store.success").asLong()).isZero();
            assertThat(counters.path("store.error").asLong()).isZero();
            assertListSql(sql, 1);
        } else {
            assertThat(evidence.path("sql")).hasSize(5);
            assertThat(counters.path("lookup.miss").asLong()).isEqualTo(2);
            assertThat(counters.path("store.success").asLong() + counters.path("store.error").asLong()).isEqualTo(1);
            assertListSql(sql, 2);
        }
        if (condition.equals("empty")) {
            assertThat(outcome).isNotEqualTo("HIT");
        }
        if (condition.equals("retained")) {
            // 키 존재와 실제 hit를 구분한다. 최초 연결 오류를 버리거나 재시도해 hit로 포장하지 않는다.
            assertThat(outcome).isIn("HIT", "GET_ERROR_DB_FALLBACK");
        }
        return outcome;
    }

    private void assertListSql(List<String> sql, int revisionReads) {
        assertThat(sql.stream().filter(query -> query.contains("from product_list_revision")).count()).isEqualTo(revisionReads);
        assertThat(sql.stream().filter(query -> query.startsWith("select count(") && query.contains("from product ")).count()).isEqualTo(1);
        assertThat(sql.stream().filter(query -> query.contains("left join `like`") && query.contains("group by")
            && query.contains("limit")).count()).isEqualTo(1);
        assertThat(sql.stream().filter(query -> query.contains(" in (") && !query.contains("join `like`")).count()).isEqualTo(1);
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

    private RunningServer start(String label) throws Exception {
        Path directory = Files.createDirectory(runDirectory.resolve(label));
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
            "--commerce.product-list-cache.enabled=true", "--commerce.product-list-cache.namespace=" + NAMESPACE));
        long launched = System.nanoTime();
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(directory.resolve("server.log").toFile()).start();
        var server = new RunningServer(process, directory);
        try {
            awaitFile(directory.resolve("ready.properties"), process, Duration.ofSeconds(45));
            server.readyObservedNanos = System.nanoTime();
            server.launchToReadyNanos = server.readyObservedNanos - launched;
            try (var input = Files.newInputStream(directory.resolve("ready.properties"))) {
                server.ready.load(input);
            }
            assertThat(Long.parseLong(server.ready.getProperty("pid"))).isEqualTo(process.pid());
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

    private Map<String, Object> report(JdbcTemplate jdbc) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("completed", false);
        report.put("request", REQUEST);
        report.put("pairs", PAIRS);
        report.put("ttlSeconds", 60);
        report.put("namespace", NAMESPACE);
        report.put("seed", "fixtures/week4/product-query-baseline.sql: products=10000, visible=9812, likes=46955");
        report.put("seedSha256", sha(Files.readAllBytes(Path.of("src/test/resources/fixtures/week4/product-query-baseline.sql"))));
        report.put("repositorySha256", sha(Files.readAllBytes(Path.of("src/main/java/com/loopers/infrastructure/product/ProductQueryRepositoryImpl.java"))));
        report.put("harnessSha256", sha(Files.readAllBytes(Path.of("src/test/java/com/loopers/application/product/ProductListColdStartMeasurementTest.java"))));
        report.put("childHarnessSha256", sha(Files.readAllBytes(Path.of("src/test/java/w4/measurement/ColdStartServer.java"))));
        report.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("schema", SCHEMA);
        report.put("conditions", "16 distinct fresh app JVMs; retained/empty alternated in eight counterbalanced pairs; "
            + "same owned DB/Redis kept running; actual predecessor app stores cache with unchanged 60s TTL; "
            + "only exact owned key deleted for empty condition; no readiness HTTP or target-JVM warmup; "
            + "production runtime classpath plus isolated test observer classes; test profile, ddl-auto=none, heap128m/512m");
        report.put("limitations", List.of("Only application JVM is cold. DB buffer, Redis process and OS filesystem cache are not reset.",
            "First HTTP includes fresh server/JPA/Redis initialization and local networking; startup-to-ready is separate.",
            "HTTP timing stops after reading body bytes/string; parent JSON assertions and child diagnostic serialization are outside timing.",
            "Test-only JDBC/filter observer overhead remains; SQL execute excludes ResultSet consumption.",
            "Second request follows diagnostic capture and is not a steady-state benchmark.",
            "Eight samples per condition; report raw samples, median and range, not a reliable p95 or production SLO.",
            "Cache-present condition may fall back on a cold connection error; preserve and classify those samples.",
            "No application warmup feature, TTL change, schema/index change, concurrent writers or shared data deletion."));
        report.put("samples", samples);
        report.put("preparations", preparations);
        return report;
    }

    private Map<String, Object> summaries() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String condition : List.of("retained", "empty")) {
            List<Map<String, Object>> group = samples.stream().filter(sample -> sample.get("condition").equals(condition)).toList();
            Map<String, Long> outcomes = new LinkedHashMap<>();
            group.forEach(sample -> outcomes.merge(mapper.valueToTree(sample.get("first")).path("outcome").asText(), 1L, Long::sum));
            result.put(condition, Map.of("count", group.size(), "firstHttpMillis", distribution(group, "first"),
                "secondHttpMillis", distribution(group, "second"), "firstOutcomes", outcomes,
                "launchToReadyMillis", distribution(group.stream().map(sample -> (Double) sample.get("launchToReadyObservedMillis")).toList())));
        }
        return result;
    }

    private Map<String, Double> distribution(List<Map<String, Object>> group, String phase) {
        return distribution(group.stream().map(sample -> mapper.valueToTree(sample.get(phase)).path("httpMillis").asDouble()).toList());
    }

    private Map<String, Double> distribution(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return Map.of("min", sorted.getFirst(), "median", n % 2 == 0
            ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2 : sorted.get(n / 2), "max", sorted.getLast());
    }

    private void save(Map<String, Object> report) throws IOException {
        Files.writeString(OUTPUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private long ttl() {
        Long remaining = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        return remaining == null ? -3 : remaining;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertDatabase(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(46955);
        assertThat(jdbc.queryForObject("SELECT revision FROM product_list_revision WHERE id=1", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN brand b ON b.id=p.brand_id "
            + "WHERE p.deleted_at IS NULL AND b.deleted_at IS NULL", Long.class)).isEqualTo(9812);
    }

    private JsonNode expectedPage() {
        var indices = IntStream.rangeClosed(1, 10000).filter(index -> index % 100 != 0 && index % 113 != 0)
            .boxed().sorted(Comparator.<Integer>comparingInt(ProductListColdStartMeasurementTest::likes).reversed()
                .thenComparing(Comparator.reverseOrder())).toList();
        ObjectNode page = mapper.createObjectNode();
        var items = page.putArray("items");
        indices.stream().limit(20).forEach(index -> {
            var item = items.addObject();
            item.put("productId", 100000 + index).put("name", "W4 product %05d".formatted(index))
                .put("price", 1000 + index * 37 % 1000 * 100)
                .put("stockQuantity", index % 17 == 0 ? 0 : 1 + index % 50).put("likeCount", likes(index));
            int brand = 1001 + (index - 1) % 100;
            item.putObject("brand").put("brandId", brand).put("name", "W4 brand %03d".formatted(brand - 1000));
        });
        return page.put("page", 0).put("size", 20).put("totalElements", indices.size()).put("totalPages", 491);
    }

    private static int likes(int index) {
        return index % 997 == 0 ? 200 : ((index - 1) / 100 + index) % 10;
    }

    private static void awaitFile(Path path, Process process, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException("Child signal unavailable: " + path + "; alive=" + process.isAlive()
                + "; see " + path.getParent().resolve("server.log"));
        }
    }

    private record Response(long elapsedNanos, int status, JsonNode body) { }

    private final class RunningServer implements AutoCloseable {
        private final Process process;
        private final Path directory;
        private final BufferedWriter input;
        private final Properties ready = new Properties();
        private long readyObservedNanos;
        private long launchToReadyNanos;
        private int captures;

        private RunningServer(Process process, Path directory) {
            this.process = process;
            this.directory = directory;
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
                    // JUnit timeout이어도 실험 프로세스를 남기지 않고, 종료 정리 뒤 인터럽트를 복원한다.
                    interrupted[0] = true;
                }
            }
            return true;
        }
    }
}
