package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.order.SpringAppProcess;
import experiments.coupon.CouponTimingApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 직접 발급과 Redis 앞단 판단 비교: DB_ONLY / Redis 판단 뒤 동기 DB 발급(REDIS_SYNC) /
 * Redis 판단 + 작업 큐 등록 뒤 202, Worker가 DB 저장(QUEUE). QUEUE에서 "받음"은 202이고 createdMs는 접수 응답 시간이다.
 * 같은 PC에서 MySQL·Redis·앱·k6를 실행하고 실제 요청·발급·오류·상품 지연·연결/행 잠금 대기를 기록한다.
 * Kafka·consumer는 기동하지 않는다. Redis는 두 조건 모두 실행한다.
 * 환경 변수: COUPON_LOAD_MODE, COUPON_LOAD_STAGES(초당 요청:초), COUPON_LOAD_QUOTA,
 * COUPON_LOAD_POOL_SIZE, COUPON_LOAD_PRODUCT_RATE, COUPON_LOAD_DUPLICATE_EVERY,
 * COUPON_LOAD_REPEATS, COUPON_LOAD_LABEL, COUPON_LOAD_JDBC_TIMING,
 * COUPON_LOAD_GATE_STORE(REDIS_SYNC의 판단 정보 위치: REDIS 또는 LOCAL=앱 메모리),
 * COUPON_LOAD_LIMIT(DB 발급 TX 앞 대기: NONE, LOCK=Redisson 분산락, SEMAPHORE=Redisson 세마포어), COUPON_LOAD_LIMIT_PERMITS,
 * COUPON_LOAD_LIMIT_WAIT(자리 대기 최대 시간, 0s면 바로 거절),
 * COUPON_LOAD_WORKER_SAVE_IN_BATCH(QUEUE Worker가 가져온 작업을 한 TX로 저장할지), COUPON_LOAD_WORKER_BATCH_SIZE.
 * 변경할 때 --rerun 필요.
 */
@Tag("experiment")
class CouponLoadExperimentTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN = "X-USER-ROLE";
    private static final List<int[]> STAGES = parseStages(env("COUPON_LOAD_STAGES", "50:10,100:10,200:10,400:10"));
    private static final int QUOTA = Integer.parseInt(env("COUPON_LOAD_QUOTA", "5000"));
    private static final int PRODUCT_RATE = Integer.parseInt(env("COUPON_LOAD_PRODUCT_RATE", "20"));
    private static final int DUPLICATE_EVERY = Integer.parseInt(env("COUPON_LOAD_DUPLICATE_EVERY", "50"));
    private static final int REPEATS = Integer.parseInt(env("COUPON_LOAD_REPEATS", "1"));
    private static final String LABEL = env("COUPON_LOAD_LABEL", "coupon-load");
    private static final String MODE = env("COUPON_LOAD_MODE", "DB_ONLY");
    private static final boolean QUEUE = "QUEUE".equals(MODE);
    /** Redis 판단을 쓰는 경로(REDIS_SYNC, QUEUE). 실행마다 판단 정보를 준비한다. */
    private static final boolean REDIS_SYNC = "REDIS_SYNC".equals(MODE) || QUEUE;
    private static final int POOL_SIZE = Integer.parseInt(env("COUPON_LOAD_POOL_SIZE", "40"));
    private static final boolean JDBC_TIMING = Boolean.parseBoolean(env("COUPON_LOAD_JDBC_TIMING", "false"));
    private static final String GATE_STORE = env("COUPON_LOAD_GATE_STORE", "REDIS");
    private static final String LIMIT = env("COUPON_LOAD_LIMIT", "NONE");
    private static final int LIMIT_PERMITS = Integer.parseInt(env("COUPON_LOAD_LIMIT_PERMITS", "3"));
    private static final String LIMIT_WAIT = env("COUPON_LOAD_LIMIT_WAIT", "60s");
    private static final boolean WORKER_SAVE_IN_BATCH = Boolean.parseBoolean(env("COUPON_LOAD_WORKER_SAVE_IN_BATCH", "true"));
    private static final int WORKER_BATCH_SIZE = Integer.parseInt(env("COUPON_LOAD_WORKER_BATCH_SIZE", "10"));
    /** 부하가 끝난 뒤 서버 작업이 끝나기를 기다리는 최대 시간(초). 넘으면 미완료로 기록한다. */
    private static final int DRAIN_SECONDS = Integer.parseInt(env("COUPON_LOAD_DRAIN_SECONDS", "120"));
    /** 자리를 못 얻어 거절된 응답의 문구(CouponRejections.LIMIT_NO_SEAT). */
    private static final String LIMIT_REJECTED = "쿠폰 발급 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    private static final int PRODUCTS = 200;
    private static final Pattern MESSAGE = Pattern.compile("msg=\"(.*)\"");

    private static MySQLContainer<?> mysql;
    private static GenericContainer<?> redis;
    private static SpringAppProcess.Database database;
    private static Connection db;
    private static long firstUserId = -1;
    private static boolean productsReady;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll
    static void startDatabase() throws Exception {
        if (!List.of("DB_ONLY", "REDIS_SYNC", "QUEUE").contains(MODE)) throw new IllegalArgumentException("Unknown coupon mode: " + MODE);
        if (!List.of("REDIS", "LOCAL").contains(GATE_STORE)) throw new IllegalArgumentException("Unknown gate store: " + GATE_STORE);
        if (!List.of("NONE", "LOCK", "SEMAPHORE").contains(LIMIT)) throw new IllegalArgumentException("Unknown issue limit: " + LIMIT);
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0")).withDatabaseName("coupon_load")
            .withCommand("--max-connections=500");
        mysql.start();
        redis = new GenericContainer<>(DockerImageName.parse("redis:7.0.15")).withExposedPorts(6379);
        redis.start();
        database = new SpringAppProcess.Database(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        db = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            db.close();
        } finally {
            redis.stop();
            mysql.stop();
        }
    }

    @Test
    void recordSynchronousIssueUnderLoad() throws Exception {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int repeat = 1; repeat <= REPEATS; repeat++) {
            Map<String, Object> run = runOnce();
            run.put("repeat", repeat);
            runs.add(run);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("stages", STAGES.stream().map(stage -> stage[0] + "/s x " + stage[1] + "s").toList());
        report.put("mode", MODE);
        report.put("gateStore", GATE_STORE);
        report.put("issueLimit", LIMIT);
        report.put("issueLimitPermits", "SEMAPHORE".equals(LIMIT) ? LIMIT_PERMITS : null);
        report.put("issueLimitWait", "NONE".equals(LIMIT) ? null : LIMIT_WAIT);
        report.put("workerSaveInBatch", QUEUE ? WORKER_SAVE_IN_BATCH : null);
        report.put("workerBatchSize", QUEUE ? WORKER_BATCH_SIZE : null);
        report.put("poolSize", POOL_SIZE);
        report.put("jdbcTimingEnabled", JDBC_TIMING);
        report.put("quota", QUOTA);
        report.put("productRate", PRODUCT_RATE);
        report.put("duplicateEvery", DUPLICATE_EVERY);
        report.put("runs", runs);
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Path out = Path.of(System.getProperty("experiment.repositoryRoot"), "build", LABEL + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        System.out.println("COUPON_LOAD_EXPERIMENT " + out);
        assertThat(runs).isNotEmpty();
        // 결과를 먼저 저장한 뒤 DB 정합성(한도·중복)을 검사한다. 실패한 실행도 원인을 볼 수 있게 남긴다.
        // "DB 발급 수 = 성공 응답 수"는 클라이언트 시간 초과로 달라질 수 있어 실패로 보지 않고
        // correctness.issuedWithoutSuccessResponse로 센다.
        for (Map<String, Object> run : runs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> correctness = (Map<String, Object>) run.get("correctness");
            long issued = ((Number) correctness.get("issuedCount")).longValue();
            assertThat(issued).as("repeat %s", run.get("repeat"))
                .isEqualTo(((Number) correctness.get("couponRows")).longValue())
                .isEqualTo(((Number) correctness.get("distinctUsers")).longValue())
                .isLessThanOrEqualTo(QUOTA);
        }
    }

    /** 제품 classpath에 선택한 계측 클래스만 덧붙여 다른 테스트 설정의 component scan 혼입을 막는다. */
    private static Path diagnosticClasspath() throws Exception {
        Path root = Files.createTempDirectory("coupon-timing-classes");
        Path target = root.resolve("experiments/coupon");
        Files.createDirectories(target);
        Path source = Path.of(CouponTimingApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("experiments/coupon");
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.filter(file -> file.toString().endsWith(".class"))
                .filter(file -> !file.getFileName().toString().contains("Test")).toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return root;
    }

    private Map<String, Object> runOnce() throws Exception {
        int port = SpringAppProcess.freePort();
        int managementPort = SpringAppProcess.freePort();
        String baseUrl = "http://127.0.0.1:" + port;
        List<String> arguments = List.of(
            "--datasource.mysql-jpa.main.maximum-pool-size=" + POOL_SIZE,
            "--datasource.mysql-jpa.main.minimum-idle=" + Math.min(30, POOL_SIZE),
            "--server.tomcat.mbeanregistry.enabled=true",
            "--datasource.redis.master.host=" + redis.getHost(),
            "--datasource.redis.master.port=" + redis.getFirstMappedPort(),
            "--datasource.redis.replicas[0].host=" + redis.getHost(),
            "--datasource.redis.replicas[0].port=" + redis.getFirstMappedPort(),
            "--commerce.coupon.issue-mode=" + MODE,
            "--commerce.coupon.issue-gate.store=" + GATE_STORE,
            "--commerce.coupon.issue-limit.mode=" + LIMIT,
            "--commerce.coupon.issue-limit.permits=" + LIMIT_PERMITS,
            "--commerce.coupon.issue-limit.wait-timeout=" + LIMIT_WAIT,
            "--commerce.coupon.issue-queue.worker.save-in-batch=" + WORKER_SAVE_IN_BATCH,
            "--commerce.coupon.issue-queue.worker.batch-size=" + WORKER_BATCH_SIZE);
        try (SpringAppProcess app = SpringAppProcess.couponApi(database, port, managementPort, arguments, JDBC_TIMING ? diagnosticClasspath() : null)) {
            awaitReady(app, managementPort);
            prepareSharedData(baseUrl);
            resetCouponTables();
            long campaignId = createCampaign();
            if (REDIS_SYNC) post(baseUrl + "/api-admin/v1/coupon-campaigns/" + campaignId + "/issue-gate", "", 200);
            return measure(app, managementPort, baseUrl, campaignId);
        }
    }

    private Map<String, Object> measure(SpringAppProcess app, int managementPort, String baseUrl, long campaignId) throws Exception {
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        List<DbSample> locks = Collections.synchronizedList(new ArrayList<>());
        Connection lockDb = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        ScheduledExecutorService sampler = Executors.newScheduledThreadPool(2);
        sampler.scheduleAtFixedRate(() -> sampleApp(managementPort, samples), 0, 500, TimeUnit.MILLISECONDS);
        sampler.scheduleAtFixedRate(() -> sampleDb(lockDb, locks), 0, 500, TimeUnit.MILLISECONDS);
        Path work = Files.createTempDirectory("coupon-load");
        List<Line> lines;
        Map<String, Object> drain;
        try {
            runK6(work, baseUrl, campaignId);
            long loadEnded = System.currentTimeMillis();
            lines = lines(work.resolve("console.log"));
            // k6가 끝나도 서버는 아직 처리 중일 수 있다(클라이언트가 먼저 포기한 요청 등). 끝날 때까지 표본을 계속 모은다.
            drain = awaitServerDrain(samples, locks, loadEnded);
        } finally {
            sampler.shutdownNow();
            sampler.awaitTermination(5, TimeUnit.SECONDS);
            lockDb.close();
        }

        long origin = lines.stream().mapToLong(Line::origin).min().orElseThrow();
        Map<String, Object> run = new LinkedHashMap<>();
        drain.put("loadEndedAtSecond", round((((Number) drain.remove("loadEndedEpochMs")).longValue() - origin) / 1000.0));
        Object drainedAt = drain.remove("drainedEpochMs");
        drain.put("drainedAtSecond", drainedAt == null ? null : round((((Number) drainedAt).longValue() - origin) / 1000.0));
        run.put("drain", drain);
        run.put("generator", generator(work.resolve("summary.json")));
        run.put("saving", saving(locks, origin));
        run.put("correctness", correctness(campaignId, lines));
        // 앱 메모리 판단은 밖에서 읽을 수 없어 Redis 상태만 기록한다.
        if (REDIS_SYNC && "REDIS".equals(GATE_STORE)) run.put("gateAfter", gateState(campaignId));
        run.put("soldOut", soldOut(lines, locks, origin));
        if (!"NONE".equals(LIMIT)) run.put("limitRejections", limitRejections(lines));
        if (QUEUE) {
            run.put("acceptToSaved", acceptToSaved(campaignId));
            run.put("queueAfter", queueState(campaignId));
        }
        run.put("stages", stageReports(lines, samples, locks, origin));
        run.put("timeline", timeline(lines, samples, locks, origin));
        run.put("originEpochMs", origin);
        run.put("rawAppSamples", List.copyOf(samples));
        run.put("rawDbSamples", List.copyOf(locks));
        if (JDBC_TIMING) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/__experiment/coupon-timings"))
                .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode timings = MAPPER.readTree(response.body());
            assertThat(timings.isArray()).isTrue();
            long issueRows = StreamSupport.stream(timings.spliterator(), false)
                .filter(row -> row.path("kind").asText().equals("issue")).count();
            long successfulRows = StreamSupport.stream(timings.spliterator(), false)
                .filter(row -> row.path("kind").asText().equals("issue") && row.path("status").asInt() == 201).count();
            assertThat(issueRows).as("Every issue response has a timing row")
                .isEqualTo(lines.stream().filter(Line::issue).count());
            assertThat(successfulRows).isEqualTo(((Number) ((Map<?, ?>) run.get("correctness")).get("accepted")).longValue());
            assertThat(StreamSupport.stream(timings.spliterator(), false)
                .filter(row -> row.path("kind").asText().equals("issue") && row.path("status").asInt() == 201)
                .allMatch(row -> row.path("connections").asInt() > 0 && row.path("couponUpdateNanos").asLong() > 0
                    && row.path("couponInsertNanos").asLong() > 0))
                .as("Successful issuance includes measured acquisition, UPDATE and INSERT").isTrue();
            assertThat(StreamSupport.stream(timings.spliterator(), false)
                .filter(row -> row.path("kind").asText().equals("product")).count())
                .isEqualTo(lines.stream().filter(line -> !line.issue()).count());
            Map<String, Line> byId = lines.stream().collect(Collectors.toMap(Line::requestId, line -> line));
            assertThat(timings.size()).isEqualTo(byId.size());
            var seenIds = new java.util.HashSet<String>();
            for (JsonNode row : timings) {
                String requestId = row.path("requestId").asText();
                assertThat(seenIds.add(requestId)).as("Unique timing ID %s", requestId).isTrue();
                Line line = byId.get(requestId);
                assertThat(line).as("Matching HTTP request %s", requestId).isNotNull();
                assertThat(row.path("status").asInt()).isEqualTo(line.status());
                ((com.fasterxml.jackson.databind.node.ObjectNode) row).put("clientHttpNanos", Math.round(line.ms() * 1_000_000));
            }
            Path raw = Path.of(System.getProperty("experiment.repositoryRoot"), "build",
                LABEL + "-requests-" + System.nanoTime() + ".json.gz");
            Files.createDirectories(raw.getParent());
            try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(raw))) { MAPPER.writeValue(output, timings); }
            run.put("requestTimingsFile", raw.getFileName().toString());
            run.put("requestTimingCount", timings.size());
        }
        run.put("hikariTimeoutsTotal", samples.stream().map(Sample::timeouts).filter(v -> v != null)
            .mapToDouble(Double::doubleValue).max().orElse(-1));
        // 거절(409 등)도 CoreException WARN 로그를 남긴다. 그 양을 기록한다.
        run.put("appLogBytes", app.logBytes());
        System.out.println("COUPON_LOAD_RUN " + MAPPER.writeValueAsString(run));
        return run;
    }

    /** 저장 쪽 흐름: 저장된 쿠폰 수가 마지막 값에 처음 닿은 시각(부하 시작 기준)과 그때의 수. */
    /**
     * 부하가 끝난 뒤 서버 작업이 끝났는지 본다. Hikari 사용 중 0, Tomcat 사용 중 1 이하(표본 요청 자신),
     * 쿠폰 수가 3초 동안 그대로일 때 끝난 것으로 본다. 쿠폰 수가 잠깐 멈춘 것만으로는 끝났다고 보지 않는다.
     * DRAIN_SECONDS 안에 끝나지 않으면 drained=false로 남긴다.
     */
    private static Map<String, Object> awaitServerDrain(List<Sample> samples, List<DbSample> locks, long loadEnded)
        throws InterruptedException {
        long deadline = loadEnded + TimeUnit.SECONDS.toMillis(DRAIN_SECONDS);
        Long idleSince = null;
        long lastCoupons = -1;
        Sample lastApp = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            synchronized (samples) {
                lastApp = samples.isEmpty() ? null : samples.get(samples.size() - 1);
            }
            DbSample lastDb;
            synchronized (locks) {
                lastDb = locks.isEmpty() ? null : locks.get(locks.size() - 1);
            }
            boolean idle = lastApp != null && lastDb != null && lastApp.active() != null && lastApp.active() == 0
                && lastApp.busyThreads() != null && lastApp.busyThreads() <= 1 && lastDb.coupons() == lastCoupons;
            lastCoupons = lastDb == null ? -1 : lastDb.coupons();
            if (!idle) {
                idleSince = null;
                continue;
            }
            if (idleSince == null) idleSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - idleSince >= 3_000) {
                return drainResult(loadEnded, idleSince, true, lastApp);
            }
        }
        return drainResult(loadEnded, null, false, lastApp);
    }

    private static Map<String, Object> drainResult(long loadEnded, Long drainedAt, boolean drained, Sample lastApp) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("drained", drained);
        result.put("loadEndedEpochMs", loadEnded);
        result.put("drainedEpochMs", drainedAt);
        result.put("waitLimitSeconds", DRAIN_SECONDS);
        result.put("lastTomcatBusy", lastApp == null ? null : lastApp.busyThreads());
        result.put("lastHikariActive", lastApp == null ? null : lastApp.active());
        return result;
    }

    /**
     * 작업 큐에서 접수부터 DB 저장까지 걸린 시간. 발급 시각(issued_at)이 접수 시각이고 created_at이 INSERT 시각이다.
     * 둘 다 앱 시계라 시계 차이는 없다. created_at은 commit 시각이 아니므로 조회 가능 시각과는 조금 다를 수 있다.
     */
    private static Map<String, Object> acceptToSaved(long campaignId) throws SQLException {
        List<Double> ms = new ArrayList<>();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT TIMESTAMPDIFF(MICROSECOND, issued_at, created_at) FROM coupon WHERE campaign_id = " + campaignId)) {
            while (rows.next()) ms.add(rows.getLong(1) / 1000.0);
        }
        Collections.sort(ms);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", ms.size());
        if (!ms.isEmpty()) {
            result.put("p50", round(ms.get((int) Math.floor((ms.size() - 1) * 0.50))));
            result.put("p95", round(ms.get((int) Math.floor((ms.size() - 1) * 0.95))));
            result.put("p99", round(ms.get((int) Math.floor((ms.size() - 1) * 0.99))));
            result.put("max", round(ms.get(ms.size() - 1)));
        }
        return result;
    }

    /** 실험 뒤 작업 큐 상태. 본문 길이와 미전달(lag)·미완료(pending)·보류(held)를 구분한다. */
    private static Map<String, Object> queueState(long campaignId) throws Exception {
        String key = "coupon-issue:{" + campaignId + "}:jobs";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("length", redis.execInContainer("redis-cli", "XLEN", key).getStdout().trim());
        String pending = redis.execInContainer("redis-cli", "XPENDING", key, "coupon-issue-workers").getStdout().trim();
        state.put("pending", pending.isEmpty() ? "?" : pending.split("\\R")[0]);
        return state;
    }

    private static Set<Long> outcomeUsers(long campaignId) throws SQLException {
        Set<Long> users = new HashSet<>();
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery("SELECT user_id FROM coupon WHERE campaign_id = " + campaignId)) {
            while (rows.next()) users.add(rows.getLong(1));
        }
        return users;
    }

    private static Map<String, Object> saving(List<DbSample> locks, long origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<DbSample> samples;
        synchronized (locks) {
            samples = new ArrayList<>(locks);
        }
        long finalCount = samples.stream().mapToLong(DbSample::coupons).max().orElse(-1);
        samples.stream().filter(sample -> sample.coupons() == finalCount).findFirst()
            .ifPresent(sample -> result.put("allSavedAtSecond", round((sample.epochMs() - origin) / 1000.0)));
        result.put("savedCoupons", finalCount);
        return result;
    }

    /**
     * 부하 발생기가 목표 속도를 실제로 보냈는지. dropped_iterations는 VU가 모자라 시작하지 못한 요청 수다.
     * 이 값이 0보다 크면 서버가 아니라 측정 환경이 목표 유입을 만들지 못한 것이다.
     */
    private static Map<String, Object> generator(Path summaryFile) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode metrics = MAPPER.readTree(summaryFile.toFile()).path("metrics");
        result.put("iterations", metrics.path("iterations").path("values").path("count").asLong());
        result.put("iterationsPerSecond", round(metrics.path("iterations").path("values").path("rate").asDouble()));
        result.put("droppedIterations", metrics.path("dropped_iterations").path("values").path("count").asLong(0));
        result.put("vusMax", metrics.path("vus_max").path("values").path("max").asLong());
        return result;
    }

    // ---- 부하 ----

    private void runK6(Path work, String baseUrl, long campaignId) throws Exception {
        List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        for (int[] stage : STAGES) {
            offsets.add(offset);
            offset += usersFor(stage);
        }
        Path config = work.resolve("config.json");
        Files.writeString(config, MAPPER.writeValueAsString(Map.of("baseUrl", baseUrl, "campaignId", campaignId,
            "firstUserId", firstUserId, "userOffsets", offsets, "productRate", PRODUCT_RATE,
            "duplicateEvery", DUPLICATE_EVERY,
            "stages", STAGES.stream().map(stage -> Map.of("rate", stage[0], "seconds", stage[1])).toList())));
        Path script = Path.of(System.getProperty("experiment.repositoryRoot"), "scripts", "week8-coupon-load.k6.js");
        Process k6 = new ProcessBuilder(k6Binary(), "run", "--quiet", "--console-output=" + work.resolve("console.log"),
            "-e", "CONFIG_FILE=" + config, "-e", "SUMMARY_FILE=" + work.resolve("summary.json"), script.toString())
            .redirectErrorStream(true).redirectOutput(work.resolve("k6.out").toFile()).start();
        int loadSeconds = STAGES.stream().mapToInt(stage -> stage[1]).sum();
        assertThat(k6.waitFor(loadSeconds + 90L, TimeUnit.SECONDS)).as("k6 종료").isTrue();
        assertThat(k6.exitValue()).as("k6 실행:\n%s", Files.readString(work.resolve("k6.out"))).isZero();
    }

    /** 예정 요청 수보다 조금 넉넉하게 사용자 구간을 나눈다. */
    private static int usersFor(int[] stage) {
        return stage[0] * stage[1] + stage[0];
    }

    private static String k6Binary() {
        String configured = System.getenv("K6");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Files.isExecutable(Path.of("/opt/homebrew/bin/k6")) ? "/opt/homebrew/bin/k6" : "k6";
    }

    // ---- 관찰 ----

    /** Hikari 대기·사용 중 연결·누적 연결 획득 실패, 톰캣 사용 중 스레드. */
    private record Sample(long epochMs, Double pending, Double active, Double timeouts, Double busyThreads) {}

    /**
     * MySQL 누적 카운터. InnoDB 행 잠금 대기 횟수·시간(ms)·지금 기다리는 수와, 실행된 UPDATE·INSERT 문 수.
     * 실험 중 UPDATE는 발급의 조건부 UPDATE뿐이라 "DB에서 한도 판단까지 간 요청 수"로 읽는다(0건 갱신도 센다).
     * INSERT는 쿠폰 저장 시도 수다(유일 제약 위반으로 실패한 것도 센다). 서버 전체 값이라 앱과 별도 연결로 읽는다.
     */
    private record DbSample(long epochMs, long waits, long timeMs, long current, long updates, long inserts, long coupons) {}

    private static void sampleDb(Connection lockDb, List<DbSample> locks) {
        long waits = -1;
        long time = -1;
        long current = -1;
        long updates = -1;
        long inserts = -1;
        try (Statement statement = lockDb.createStatement();
             ResultSet result = statement.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ("
                 + "'Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_row_lock_current_waits', "
                 + "'Com_update', 'Com_insert')")) {
            while (result.next()) {
                switch (result.getString(1)) {
                    case "Innodb_row_lock_waits" -> waits = result.getLong(2);
                    case "Innodb_row_lock_time" -> time = result.getLong(2);
                    case "Innodb_row_lock_current_waits" -> current = result.getLong(2);
                    case "Com_update" -> updates = result.getLong(2);
                    case "Com_insert" -> inserts = result.getLong(2);
                    default -> { }
                }
            }
            long coupons = -1;
            try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM coupon")) {
                if (count.next()) {
                    coupons = count.getLong(1);
                }
            }
            locks.add(new DbSample(System.currentTimeMillis(), waits, time, current, updates, inserts, coupons));
        } catch (SQLException ignored) {
            // 관찰 실패는 측정값에서 빠질 뿐 실험을 멈추지 않는다.
        }
    }

    private void sampleApp(int managementPort, List<Sample> samples) {
        long now = System.currentTimeMillis();
        try {
            String metrics = get("http://127.0.0.1:" + managementPort + "/actuator/prometheus");
            samples.add(new Sample(now, hikari(metrics, "hikaricp_connections_pending"),
                hikari(metrics, "hikaricp_connections_active"), hikari(metrics, "hikaricp_connections_timeout_total"),
                metric(metrics, "tomcat_threads_busy_threads")));
        } catch (Exception ignored) {
            // 응답 지연으로 못 읽은 구간은 비워 둔다.
        }
    }

    private static Double hikari(String metrics, String name) {
        Matcher matcher = Pattern.compile("^" + name + "\\{[^}]*pool=\"mysql-main-pool\"[^}]*} ([0-9.eE+-]+)$",
            Pattern.MULTILINE).matcher(metrics);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private static Double metric(String metrics, String name) {
        Matcher matcher = Pattern.compile("^" + name + "(\\{[^}]*})? ([0-9.eE+-]+)$", Pattern.MULTILINE).matcher(metrics);
        return matcher.find() ? Double.parseDouble(matcher.group(2)) : null;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 응답 한 건. 로그가 수만 줄이라 JSON 객체 대신 필요한 값만 담는다(기본 테스트 힙 512MB). */
    /**
     * 자리를 못 얻어 거절된 요청과 선착순이 뒤집힌 정도. 시작 시각은 k6 응답 시각 − 응답 시간이다.
     * overtaken: 거절된 요청 중, 그보다 나중에 시작한 요청이 201을 받은 경우의 수.
     */
    private static Map<String, Object> limitRejections(List<Line> lines) {
        List<Line> rejected = lines.stream().filter(l -> l.issue() && LIMIT_REJECTED.equals(l.message())).toList();
        List<Double> createdStarts = lines.stream().filter(l -> l.issue() && l.status() == 201)
            .map(l -> l.endMs() - l.ms()).sorted().toList();
        long overtaken = rejected.stream().filter(r -> {
            double start = r.endMs() - r.ms();
            return !createdStarts.isEmpty() && createdStarts.get(createdStarts.size() - 1) > start;
        }).count();
        double earliestRejected = rejected.stream().mapToDouble(r -> r.endMs() - r.ms()).min().orElse(Double.MAX_VALUE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rejected", rejected.size());
        result.put("rejectedDistinctUsers", rejected.stream().map(Line::userId).distinct().count());
        result.put("overtaken", overtaken);
        result.put("createdAfterFirstRejection", createdStarts.stream().filter(start -> start > earliestRejected).count());
        return result;
    }

    private record Line(String kind, int stage, int status, double ms, long endMs, long origin, long userId, String message,
                        String requestId) {
        private static Line from(JsonNode node) {
            return new Line(node.path("kind").asText(), node.path("stage").asInt(), node.path("status").asInt(),
                node.path("ms").asDouble(), node.path("t").asLong(), node.path("t").asLong() - node.path("elapsed").asLong(),
                node.path("userId").asLong(-1), node.hasNonNull("message") ? node.path("message").asText() : null,
                node.path("requestId").asText());
        }

        boolean issue() {
            return !"product".equals(kind);
        }
    }

    private static List<Line> lines(Path consoleLog) throws IOException {
        List<Line> lines = new ArrayList<>();
        try (Stream<String> all = Files.lines(consoleLog)) {
            for (String line : (Iterable<String>) all::iterator) {
                Matcher matcher = MESSAGE.matcher(line);
                if (matcher.find()) {
                    lines.add(Line.from(MAPPER.readTree(matcher.group(1).replace("\\\"", "\""))));
                }
            }
        }
        return lines;
    }

    /**
     * 한도 초과·중복이 없는지 DB와 응답을 대조한다. 실제 발급 완료는 201이다.
     * 따닥 쌍은 응답 조합별로 센다. 200·409를 신규 발급 수에 넣지 않는다.
     */
    private static Map<String, Object> correctness(long campaignId, List<Line> lines) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quota", QUOTA);
        // 발급 수와 쿠폰 행 수를 한 문장으로 읽어 그 사이에 저장이 끼어들지 않게 한다.
        try (Statement statement = db.createStatement(); ResultSet counts = statement.executeQuery(
            "SELECT issued_count, (SELECT COUNT(*) FROM coupon WHERE campaign_id = " + campaignId + ") "
                + "FROM coupon_campaign WHERE id = " + campaignId)) {
            counts.next();
            result.put("issuedCount", counts.getLong(1));
            result.put("couponRows", counts.getLong(2));
        }
        result.put("distinctUsers", scalar("SELECT COUNT(DISTINCT user_id) FROM coupon WHERE campaign_id = " + campaignId));
        result.put("accepted", lines.stream().filter(l -> l.issue() && accepted(l)).count());
        result.put("acceptedDistinctUsers", lines.stream().filter(l -> l.issue() && accepted(l)).map(Line::userId).distinct().count());
        // 응답 대응: DB에 쿠폰이 있는데 그 사용자가 201·200을 받지 못한 수. 클라이언트 시간 초과 등으로 생기며 DB 정합성과 별개다.
        Set<Long> answered = lines.stream().filter(l -> l.issue() && (l.status() == 201 || l.status() == 200 || l.status() == 202))
            .map(Line::userId).collect(Collectors.toSet());
        result.put("issuedWithoutSuccessResponse", outcomeUsers(campaignId).stream().filter(user -> !answered.contains(user)).count());
        Map<String, Long> pairs = lines.stream().filter(l -> "double".equals(l.kind()))
            .collect(Collectors.groupingBy(Line::userId, Collectors.mapping(l -> String.valueOf(l.status()), Collectors.toList())))
            .values().stream().map(statuses -> statuses.stream().sorted().collect(Collectors.joining("+")))
            .collect(Collectors.groupingBy(combo -> combo, TreeMap::new, Collectors.counting()));
        result.put("doubleClickPairs", pairs);
        return result;
    }

    /** 실험이 끝난 뒤 Redis 판단 정보. 남은 수 + 통과한 사용자 수가 한도와 맞는지 DB와 대조한다. */
    private static Map<String, Object> gateState(long campaignId) throws Exception {
        String key = "coupon-issue:{" + campaignId + "}:";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("remaining", redis.execInContainer("redis-cli", "HGET", key + "stock", "remaining").getStdout().trim());
        state.put("passedUsers", redis.execInContainer("redis-cli", "HLEN", key + "users").getStdout().trim());
        return state;
    }

    /** 마지막 "받음"이 언제였고, 그 뒤에 온 발급 요청은 몇 건이며 얼마나 걸렸나. */
    private static Map<String, Object> soldOut(List<Line> lines, List<DbSample> locks, long origin) {
        Map<String, Object> result = new LinkedHashMap<>();
        long lastCreated = lines.stream().filter(l -> l.issue() && accepted(l)).mapToLong(Line::endMs).max().orElse(-1);
        if (lastCreated < 0) {
            return result;
        }
        List<Line> after = lines.stream().filter(l -> l.issue() && l.endMs() > lastCreated).toList();
        result.put("lastCreatedAtSecond", round((lastCreated - origin) / 1000.0));
        result.put("requestsAfter", after.size());
        result.put("responsesAfter", responseKinds(after));
        result.put("msAfter", percentiles(after));
        DbSample atSoldOut = lastBefore(locks, lastCreated);
        DbSample end = lastBefore(locks, Long.MAX_VALUE);
        if (atSoldOut != null && end != null) {
            result.put("dbUpdatesAfter", end.updates() - atSoldOut.updates());
        }
        return result;
    }

    private static List<Map<String, Object>> stageReports(List<Line> lines, List<Sample> samples, List<DbSample> locks,
                                                          long origin) {
        List<Map<String, Object>> reports = new ArrayList<>();
        long stageStart = origin;
        for (int index = 0; index < STAGES.size(); index++) {
            int[] stage = STAGES.get(index);
            long from = stageStart;
            long to = stageStart + stage[1] * 1_000L;
            int stageIndex = index;
            List<Line> issues = lines.stream().filter(l -> l.issue() && l.stage() == stageIndex).toList();
            List<Line> products = lines.stream().filter(l -> !l.issue() && l.stage() == stageIndex).toList();
            List<Sample> mine = window(samples, from, to);
            long created = issues.stream().filter(CouponLoadExperimentTest::accepted).count();

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("rate", stage[0]);
            report.put("seconds", stage[1]);
            report.put("sent", issues.size());
            report.put("responses", responseKinds(issues));
            report.put("createdPerSecond", round(created / (double) stage[1]));
            report.put("createdMs", percentiles(issues.stream().filter(CouponLoadExperimentTest::accepted).toList()));
            report.put("allIssueMs", percentiles(issues));
            report.put("productSent", products.size());
            report.put("productNot200", products.stream().filter(l -> l.status() != 200).count());
            report.put("productMs", percentiles(products));
            report.put("maxHikariPending", max(mine, Sample::pending));
            report.put("maxHikariActive", max(mine, Sample::active));
            report.put("maxTomcatBusyThreads", max(mine, Sample::busyThreads));
            List<DbSample> lockWindow;
            synchronized (locks) {
                lockWindow = locks.stream().filter(l -> l.epochMs() >= from && l.epochMs() < to).toList();
            }
            if (lockWindow.size() >= 2) {
                long waits = lockWindow.getLast().waits() - lockWindow.getFirst().waits();
                long timeMs = lockWindow.getLast().timeMs() - lockWindow.getFirst().timeMs();
                report.put("rowLockWaits", waits);
                report.put("rowLockAvgWaitMs", waits == 0 ? 0 : round(timeMs / (double) waits));
                report.put("maxRowLockCurrentWaits", lockWindow.stream().mapToLong(DbSample::current).max().orElse(-1));
                report.put("dbUpdates", lockWindow.getLast().updates() - lockWindow.getFirst().updates());
                report.put("dbInserts", lockWindow.getLast().inserts() - lockWindow.getFirst().inserts());
            }
            reports.add(report);
            stageStart = to;
        }
        return reports;
    }

    /** 초 단위 흐름: 응답이 끝난 시각 기준으로 발급 응답 수·"받음" 수·발급 p95·상품 p95·Hikari 대기·DB UPDATE 수·저장된 쿠폰 수. */
    private static List<Map<String, Object>> timeline(List<Line> lines, List<Sample> samples, List<DbSample> locks,
                                                      long origin) {
        Map<Long, List<Line>> bySecond = lines.stream()
            .collect(Collectors.groupingBy(l -> (l.endMs() - origin) / 1000, TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> seconds = new ArrayList<>();
        bySecond.forEach((second, inSecond) -> {
            List<Line> issues = inSecond.stream().filter(Line::issue).toList();
            List<Line> products = inSecond.stream().filter(l -> !l.issue()).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("s", second);
            row.put("issueDone", issues.size());
            row.put("created", issues.stream().filter(CouponLoadExperimentTest::accepted).count());
            row.put("issueP95", percentiles(issues).getOrDefault("p95", -1L));
            row.put("productP95", percentiles(products).getOrDefault("p95", -1L));
            long from = origin + second * 1000;
            row.put("hikariPending", max(window(samples, from, from + 1000), Sample::pending));
            row.put("hikariActive", max(window(samples, from, from + 1000), Sample::active));
            row.put("tomcatBusy", max(window(samples, from, from + 1000), Sample::busyThreads));
            row.put("issueKinds", responseKinds(issues));
            DbSample start = lastBefore(locks, from);
            DbSample stop = lastBefore(locks, from + 1000);
            row.put("dbUpdates", start == null || stop == null ? -1 : stop.updates() - start.updates());
            row.put("savedCoupons", stop == null ? -1 : stop.coupons());
            row.put("rowLockWaitsDelta", start == null || stop == null ? -1 : stop.waits() - start.waits());
            row.put("rowLockTimeDeltaMs", start == null || stop == null ? -1 : stop.timeMs() - start.timeMs());
            synchronized (locks) {
                row.put("rowLockCurrentMax", locks.stream().filter(sample -> sample.epochMs() >= from && sample.epochMs() < from + 1000)
                    .mapToLong(DbSample::current).max().orElse(-1));
            }
            seconds.add(row);
        });
        return seconds;
    }

    /** 시각 이전의 마지막 DB 표본. 0.5초 간격이라 초 단위 값은 근사다. */
    private static DbSample lastBefore(List<DbSample> locks, long epochMs) {
        synchronized (locks) {
            DbSample found = null;
            for (DbSample sample : locks) {
                if (sample.epochMs() <= epochMs) {
                    found = sample;
                }
            }
            return found;
        }
    }

    private static List<Sample> window(List<Sample> samples, long from, long to) {
        synchronized (samples) {
            return samples.stream().filter(s -> s.epochMs() >= from && s.epochMs() < to).toList();
        }
    }

    private static double max(List<Sample> samples, java.util.function.Function<Sample, Double> value) {
        return samples.stream().map(value).filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(-1);
    }

    /** 409 중 "처리 중"(따닥의 뒤 요청)은 품절·종료와 의미가 달라 따로 센다. */
    private static Map<String, Long> responseKinds(List<Line> lines) {
        Map<String, Long> kinds = new TreeMap<>();
        for (Line line : lines) {
            String kind = switch (line.status()) {
                case 0 -> "transport-error";
                case 409 -> line.message() != null && (line.message().contains("처리 중") || line.message().contains("확인하고 있습니다")) ? "409-in-progress" : "409";
                default -> String.valueOf(line.status());
            };
            kinds.merge(kind, 1L, Long::sum);
        }
        return kinds;
    }

    /** "받음" 응답. 동기 경로는 201(발급 완료), 작업 큐는 202(접수)다. */
    private static boolean accepted(Line line) {
        return line.status() == 201 || line.status() == 202;
    }

    private static Map<String, Long> percentiles(List<Line> lines) {
        List<Long> values = lines.stream().map(l -> Math.round(l.ms())).sorted().toList();
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("p50", values.get((int) Math.ceil(0.50 * values.size()) - 1));
        result.put("p95", values.get((int) Math.ceil(0.95 * values.size()) - 1));
        result.put("p99", values.get((int) Math.ceil(0.99 * values.size()) - 1));
        result.put("max", values.getLast());
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    // ---- 앱·데이터 준비 ----

    private void awaitReady(SpringAppProcess app, int managementPort) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            assertThat(app.isAlive()).as("앱 실행:\n%s", app.logTail()).isTrue();
            try {
                if (get("http://127.0.0.1:" + managementPort + "/actuator/health").contains("\"UP\"")) {
                    return;
                }
            } catch (IOException notYet) {
                // 아직 뜨는 중이다.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("앱이 준비되지 않았습니다:\n" + app.logTail());
    }

    /** 사용자와 상품은 한 번만 만든다. ddl-auto=update라 앱을 다시 띄워도 남는다. */
    private void prepareSharedData(String baseUrl) throws Exception {
        if (firstUserId < 0) {
            int users = STAGES.stream().mapToInt(CouponLoadExperimentTest::usersFor).sum();
            long before = scalar("SELECT COALESCE(MAX(id), 0) FROM users");
            try (Statement statement = db.createStatement()) {
                statement.execute("SET SESSION cte_max_recursion_depth = 1000000");
                statement.executeUpdate("INSERT INTO users (created_at, updated_at) "
                    + "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < " + users + ") "
                    + "SELECT NOW(6), NOW(6) FROM seq");
            }
            firstUserId = before + 1;
            assertThat(scalar("SELECT COUNT(*) FROM users WHERE id >= " + firstUserId)).isEqualTo(users);
        }
        if (!productsReady) {
            JsonNode brand = MAPPER.readTree(post(baseUrl + "/api-admin/v1/brands", "{\"name\":\"부하 브랜드\",\"description\":\"실험\"}"));
            long brandId = brand.path("data").path("id").asLong();
            for (int i = 0; i < PRODUCTS; i++) {
                post(baseUrl + "/api-admin/v1/products",
                    "{\"brandId\":" + brandId + ",\"name\":\"상품" + i + "\",\"price\":" + (1_000 + i) + ",\"initialStockQuantity\":100}");
            }
            productsReady = true;
        }
    }

    private String post(String url, String body) throws IOException, InterruptedException {
        return post(url, body, 201);
    }

    private String post(String url, String body, int expectedStatus) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
            .header(ADMIN, "ADMIN").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("준비 요청 %s: %s", url, response.body()).isEqualTo(expectedStatus);
        return response.body();
    }

    /** 앞 실행의 쿠폰이 쌓이지 않게 매 실행을 같은 출발선에서 시작한다. 사용자·상품은 읽기만 하므로 유지한다. */
    private static void resetCouponTables() throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute("TRUNCATE TABLE coupon");
            statement.execute("DELETE FROM coupon_campaign");
        }
    }

    private static long createCampaign() throws SQLException {
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO coupon_campaign "
            + "(name, benefit_description, active, total_quota, issued_count, end_at, created_at, updated_at) "
            + "VALUES ('부하 실험', '5,000원 할인권', true, ?, 0, NOW(6) + INTERVAL 1 DAY, NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, QUOTA);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<int[]> parseStages(String value) {
        return Arrays.stream(value.split(",")).map(String::trim)
            .map(stage -> stage.split(":")).map(parts -> new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])})
            .toList();
    }
}
