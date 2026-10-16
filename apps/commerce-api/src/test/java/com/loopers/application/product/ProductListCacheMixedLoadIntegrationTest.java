package com.loopers.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.CommerceApiApplication;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductSort;
import com.loopers.support.RequestSqlProbe;
import com.loopers.support.RequestStageProbe;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/** TTL을 바꾸지 않고 실제 HTTP 읽기·쓰기 혼합 비용만 비교한다. 운영 성능 합격선을 만들지 않는다. */
@SpringBootTest(classes = CommerceApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false",
        "commerce.product-list-cache.enabled=true", "commerce.product-list-cache.ttl=60s",
        "commerce.product-list-cache.namespace=commerce:test:mixed:v1"})
@TypeExcludeFilters(ProductListCacheMixedLoadIntegrationTest.ContainerExclusion.class)
@Import(ProductListCacheMixedLoadIntegrationTest.DiagnosticsConfiguration.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductListCacheMixedLoadIntegrationTest {
    private static final String REQUEST = "/api/v1/products?sort=likes_desc&page=0&size=20";
    private static final String LIKE_REQUEST = "/api/v1/products/100997/likes";
    private static final String NAMESPACE = "commerce:test:mixed:v1";
    private static final String REQUEST_ID = "X-W4-SAMPLE-ID";
    private static final int READS = 200;
    private static final RequestStageProbe STAGES = new RequestStageProbe(ProductListCacheMixedLoadIntegrationTest::requestId);
    private static final ThreadLocal<Boolean> IN_SINGLE_FLIGHT = ThreadLocal.withInitial(() -> false);
    private static final List<String> SCHEMA = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql", "007-product-brand-price-index.sql",
        "008-product-list-revision.sql");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w4_cache_mixed")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
        .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
        .withExposedPorts(6379).withStartupTimeout(Duration.ofMinutes(2));
    private static boolean schemaApplied;

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MeterRegistry meters;
    @Autowired private ProductListReader reader;
    @Autowired @Qualifier("productListCacheRedisTemplate") private StringRedisTemplate redis;
    @MockitoSpyBean private ProductListCache cache;
    @MockitoSpyBean private ProductListSingleFlight singleFlight;
    @MockitoSpyBean(name = "mySqlMainDataSource") private HikariDataSource dataSource;

    private final RequestSqlProbe probe = new RequestSqlProbe(ProductListCacheMixedLoadIntegrationTest::requestId);

    private static String requestId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getHeader(REQUEST_ID) : null;
    }
    private JsonNode unchanged;
    private JsonNode liked;

    @DynamicPropertySource
    static synchronized void ownInfrastructure(DynamicPropertyRegistry properties) {
        MYSQL.start();
        REDIS.start();
        if (!schemaApplied) {
            ResourceDatabasePopulator schema = new ResourceDatabasePopulator();
            SCHEMA.forEach(file -> schema.addScript(new ClassPathResource("db/schema/" + file)));
            schema.execute(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
            schemaApplied = true;
        }
        properties.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        properties.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        properties.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
        properties.add("datasource.redis.database", () -> 0);
        properties.add("datasource.redis.master.host", REDIS::getHost);
        properties.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        properties.add("datasource.redis.replicas[0].host", REDIS::getHost);
        properties.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }

    @BeforeAll
    void seed() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/week4/product-query-baseline.sql"))
            .execute(dataSource);
        jdbc.setQueryTimeout(20);
        assertThat(jdbc.queryForList("ANALYZE TABLE brand, product, `like`, `user`"))
            .allSatisfy(row -> assertThat(row.get("Msg_text")).isEqualTo("OK"));
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(10000);
        http.getRestTemplate().setRequestFactory(requests);
        // 기대값은 운영 조회 구현이 아닌 결정적 fixture의 규칙으로 구성한다.
        unchanged = expectedPage(false);
        liked = expectedPage(true);
        assertThat(unchanged).isNotEqualTo(liked);
        assertThat(unchanged.path("totalElements").asInt()).isEqualTo(9812);
        assertThat(liked.path("items").get(0).path("productId").asLong()).isEqualTo(100997);
    }

    @BeforeEach
    void installProbe() throws Exception {
        Object transactionalReader = ((Advised) reader).getTargetSource().getTarget();
        assertThat(transactionalReader).isInstanceOf(Advised.class);
        assertThat(((Advised) transactionalReader).getAdvisors())
            .anyMatch(advisor -> advisor.getAdvice() instanceof TransactionInterceptor);
        doAnswer(invocation -> probe.wrap((Connection) invocation.callRealMethod())).when(dataSource).getConnection();
        doAnswer(invocation -> STAGES.measure(IN_SINGLE_FLIGHT.get() ? "cache.recheck" : "cache.lookup", invocation.getArgument(0),
            () -> (Optional<?>) invocation.callRealMethod(),
            result -> result.map(snapshot -> ((ProductListSnapshot) snapshot).revision()).orElse(null),
            result -> result.isPresent() ? "hit" : "miss")).when(cache).find(anyLong());
        doAnswer(invocation -> {
            ProductListSnapshot snapshot = invocation.getArgument(0);
            return STAGES.measure("cache.store", snapshot.revision(), invocation::callRealMethod,
                result -> snapshot.revision(), result -> "ok");
        }).when(cache).store(any(ProductListSnapshot.class));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void comparesCacheReadsWithRealLikeChangesAndPreservesResponseAndFinalDatabase() throws Exception {
        List<Map<String, Object>> phases = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("completed", false);
        report.put("request", REQUEST);
        report.put("ttlSeconds", 60);
        report.put("seed", Map.of("products", 10000, "likes", 46955, "visibleProducts", 9812));
        report.put("schema", SCHEMA);
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("mysqlVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        report.put("conditions", "200 scheduled reads/50ms; four readers; one writer at +25ms, then 500ms or 100ms; "
            + "10 warmups; DB-only/cache-only/coalesced retain revision writes; two counterbalanced rounds; no TTL sweep; "
            + "18 measured phases instead of 12, plus six bursts; five-minute test safety bound, not a latency SLO");
        report.put("diagnostics", "test-only outer reader/service proxies and cache spy; monotonic JVM timestamps; "
            + "no extra DB/Redis calls inside a request; stage percentiles are not additive; "
            + "SQL execute excludes result fetch; cache methods include JSON processing; instrumentation overhead is included");
        report.put("phases", phases);
        Path output = Path.of("build/reports/week4/product-list-single-flight-comparison.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        try {
            for (int round = 1; round <= 2; round++) {
                for (int writes : round == 1 ? List.of(0, 20, 100) : List.of(100, 20, 0)) {
                    for (Mode mode : round == 1 ? List.of(Mode.DATABASE, Mode.CACHE_ONLY, Mode.COALESCED)
                        : List.of(Mode.COALESCED, Mode.CACHE_ONLY, Mode.DATABASE)) {
                        phases.add(measure(round, writes, mode));
                    }
                }
            }
            for (int repeat = 1; repeat <= 3; repeat++) {
                for (Mode mode : repeat % 2 == 1 ? List.of(Mode.CACHE_ONLY, Mode.COALESCED)
                    : List.of(Mode.COALESCED, Mode.CACHE_ONLY)) {
                    phases.add(measureBurst(repeat, mode));
                }
            }
            report.put("completed", true);
        } catch (Exception | AssertionError failure) {
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private Map<String, Object> measure(int round, int writes, Mode mode) throws Exception {
        boolean enabled = mode.cacheEnabled;
        configure(mode);
        assertSuccess(exchange(HttpMethod.DELETE, LIKE_REQUEST, null));
        var keys = redis.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys); // 이 클래스 소유 Redis의 전용 namespace만 정리한다.
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(46955);
        for (int index = 0; index < 10; index++) {
            assertThat(assertSuccess(exchange(HttpMethod.GET, REQUEST, null))).isEqualTo(unchanged);
        }
        long revisionBefore = reader.currentRevision();
        long ttlObservedAt = System.nanoTime();
        long initialTtlMillis = enabled ? redis.getExpire(key(revisionBefore), TimeUnit.MILLISECONDS) : -1;
        if (enabled) {
            assertThat(initialTtlMillis).isBetween(1L, 60000L);
        }
        Map<String, Long> before = counters();
        ScheduledExecutorService readers = Executors.newScheduledThreadPool(4);
        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        List<ScheduledFuture<Sample>> futures = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        List<RequestSqlProbe.Event> sql;
        List<RequestStageProbe.Event> stages;
        long origin = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        probe.start();
        STAGES.start();
        try {
            for (int index = 0; index < READS; index++) {
                schedule(readers, futures, "read-" + index, HttpMethod.GET, index * 50L, origin);
            }
            for (int index = 0; index < writes; index++) {
                schedule(writer, futures, "write-" + index, index % 2 == 0 ? HttpMethod.POST : HttpMethod.DELETE,
                    25L + index * (10000L / writes), origin);
            }
            long deadline = origin + TimeUnit.SECONDS.toNanos(35);
            for (var future : futures) {
                samples.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
        } finally {
            readers.shutdownNow();
            writer.shutdownNow();
            boolean readsStopped;
            boolean writesStopped;
            try {
                readsStopped = readers.awaitTermination(12, TimeUnit.SECONDS);
                writesStopped = writer.awaitTermination(12, TimeUnit.SECONDS);
            } finally {
                sql = probe.stop();
                stages = STAGES.stop();
            }
            assertThat(readsStopped && writesStopped).as("측정 worker 자원 정리").isTrue();
        }
        Map<String, Long> delta = counters();
        delta.replaceAll((name, count) -> count - before.get(name));
        long elapsed = samples.stream().mapToLong(sample -> sample.startOffsetNanos() + sample.httpNanos()).max().orElseThrow();
        if (enabled) {
            // TTL 확인 뒤 준비 구간도 포함한다. 지연 목표가 아닌 실험 전제다.
            assertThat(origin + elapsed - ttlObservedAt)
                .as("TTL 만료가 아닌 버전 변경을 관찰하는 실험 전제")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(initialTtlMillis));
        }
        assertThat(samples).hasSize(READS + writes).allSatisfy(sample -> {
            assertThat(sample.status()).as(sample.id() + ": " + sample.error()).isEqualTo(200);
            assertThat(sample.valid()).as(sample.id() + ": 전체 DTO 또는 좋아요 변경 응답").isTrue();
        });
        assertThat(sql).isNotEmpty().allSatisfy(event -> assertThat(event.success()).isTrue());
        assertThat(delta.get("lookup.invalid") + delta.get("lookup.error") + delta.get("store.error")).isZero();
        var initialLookups = stages.stream().filter(event -> event.stage().equals("cache.lookup")).toList();
        var rechecks = stages.stream().filter(event -> event.stage().equals("cache.recheck")).toList();
        assertThat(initialLookups).hasSize(enabled ? READS : 0);
        if (!mode.singleFlightEnabled) {
            assertThat(rechecks).isEmpty();
        }
        for (String outcome : List.of("hit", "miss")) {
            assertThat(delta.get("lookup." + outcome)).isEqualTo(stages.stream()
                .filter(event -> event.stage().equals("cache.lookup") || event.stage().equals("cache.recheck"))
                .filter(event -> event.outcome().equals(outcome)).count());
        }
        long snapshots = stages.stream().filter(event -> event.stage().equals("db.snapshot")).count();
        long directReads = stages.stream().filter(event -> event.stage().equals("db.direct")).count();
        assertThat(delta.get("store.success")).isEqualTo(snapshots);
        assertThat(stages.stream().filter(event -> event.stage().equals("cache.store")).count()).isEqualTo(snapshots);
        var readSql = sql.stream().filter(event -> event.requestId().startsWith("read-")).toList();
        assertThat(readSql.stream().filter(event -> event.sql().startsWith("select ")).count())
            .isEqualTo((enabled ? READS : 0L) + 4 * snapshots + 3 * directReads);
        assertThat(readSql.stream().filter(event -> aggregate(event.sql())).count())
            .isEqualTo(snapshots + directReads);
        assertThat(readSql.stream().filter(event -> count(event.sql())).count())
            .isEqualTo(snapshots + directReads);
        assertThat(readSql.stream().filter(event -> detail(event.sql())).count())
            .isEqualTo(snapshots + directReads);
        var revisionSql = sql.stream().filter(event -> revisionUpdate(event.sql())).toList();
        assertThat(revisionSql).hasSize(writes);
        Map<String, List<RequestSqlProbe.Event>> byId = sql.stream().collect(Collectors.groupingBy(RequestSqlProbe.Event::requestId));
        assertThat(byId.keySet()).containsExactlyInAnyOrderElementsOf(samples.stream().map(Sample::id).toList());
        samples.stream().filter(sample -> sample.id().startsWith("write-")).forEach(sample ->
            assertThat(byId.get(sample.id()).stream().filter(event -> revisionUpdate(event.sql())).count()).isEqualTo(1));
        assertThat(reader.currentRevision()).isEqualTo(revisionBefore + writes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like` WHERE user_id=1 AND product_id=100997", Long.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(46955);
        assertThat(assertSuccess(exchange(HttpMethod.GET, REQUEST, null))).isEqualTo(unchanged);
        // 짝수 변경은 초기 상태로 돌아온다. 초기 캐시도 우연히 통과하지 않도록 별도 변경을 검사한다.
        assertSuccess(exchange(HttpMethod.POST, LIKE_REQUEST, null));
        assertThat(reader.currentRevision()).isEqualTo(revisionBefore + writes + 1);
        JsonNode afterSentinel = assertSuccess(exchange(HttpMethod.GET, REQUEST, null));
        assertThat(afterSentinel).isEqualTo(liked);
        assertThat(afterSentinel).isEqualTo(mapper.readTree(mapper.writeValueAsString(
            reader.readList(null, 0, 20, ProductSort.LIKES_DESC))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "scheduled-mixed");
        result.put("round", round);
        result.put("mode", mode.name());
        result.put("cacheEnabled", enabled);
        result.put("singleFlightEnabled", mode.singleFlightEnabled);
        result.put("writes", writes);
        result.put("revisionBefore", revisionBefore);
        result.put("revisionAfterMeasuredWrites", revisionBefore + writes);
        result.put("initialTtlMillis", initialTtlMillis);
        result.put("completedWindowMillis", elapsed / 1_000_000.0);
        result.put("ttlDidNotExpireDuringMeasurement", enabled ? true : "not applicable");
        result.put("completedReadsPerSecond", READS * 1_000_000_000.0 / elapsed);
        result.put("counters", delta);
        result.put("initialLookupHits", initialLookups.stream().filter(event -> event.outcome().equals("hit")).count());
        result.put("initialLookupMisses", initialLookups.stream().filter(event -> event.outcome().equals("miss")).count());
        result.put("leaderRechecks", rechecks.size());
        result.put("reads", summary(samples.stream().filter(sample -> sample.id().startsWith("read-")).toList()));
        result.put("writeRequests", summary(samples.stream().filter(sample -> sample.id().startsWith("write-")).toList()));
        result.put("readSelects", readSql.stream().filter(event -> event.sql().startsWith("select ")).count());
        result.put("expensiveListAggregates", readSql.stream().filter(event -> aggregate(event.sql())).count());
        result.put("selectedProductDetailReads", readSql.stream().filter(event -> detail(event.sql())).count());
        result.put("revisionExecuteMillis", distribution(revisionSql.stream().map(RequestSqlProbe.Event::executeNanos).toList()));
        result.put("sqlTemplates", sql.stream().collect(Collectors.groupingBy(RequestSqlProbe.Event::sql,
            LinkedHashMap::new, Collectors.counting())));
        result.put("rawColumns", List.of("id", "scheduledOffsetNanos", "startOffsetNanos", "httpNanos", "status",
            "valid", "selects", "listAggregates", "revisionExecuteNanos"));
        result.put("raw", samples.stream().sorted(Comparator.comparingLong(Sample::scheduledOffsetNanos)).map(sample -> {
            var events = byId.getOrDefault(sample.id(), List.of());
            return List.of(sample.id(), sample.scheduledOffsetNanos(), sample.startOffsetNanos(), sample.httpNanos(),
                sample.status(), sample.valid(), events.stream().filter(event -> event.sql().startsWith("select ")).count(),
                events.stream().filter(event -> aggregate(event.sql())).count(),
                events.stream().filter(event -> revisionUpdate(event.sql())).mapToLong(RequestSqlProbe.Event::executeNanos).sum());
        }).toList());
        result.put("stageDiagnostics", diagnostics(samples, stages, sql, mode, revisionBefore, false));
        result.put("correctness", "all HTTP/SQL succeeded; full DTO allowed-state match; exact revision delta and DB likes; "
            + "post-measurement sentinel changes the initial DTO and fresh HTTP equals independent expectation and DB");
        System.out.println("W4 mixed round=" + round + " mode=" + mode + " writes=" + writes
            + " hit=" + delta.get("lookup.hit") + " miss=" + delta.get("lookup.miss") + " reads=" + result.get("reads"));
        return result;
    }

    private void schedule(ScheduledExecutorService executor, List<ScheduledFuture<Sample>> futures, String id,
                          HttpMethod method, long offsetMillis, long origin) {
        long offset = TimeUnit.MILLISECONDS.toNanos(offsetMillis);
        futures.add(executor.schedule(() -> sample(id, method, origin, offset),
            Math.max(0, origin + offset - System.nanoTime()), TimeUnit.NANOSECONDS));
    }

    private Map<String, Object> measureBurst(int repeat, Mode mode) throws Exception {
        configure(mode);
        assertSuccess(exchange(HttpMethod.DELETE, LIKE_REQUEST, null));
        for (int index = 0; index < 10; index++) {
            assertThat(assertSuccess(exchange(HttpMethod.GET, REQUEST, null))).isEqualTo(unchanged);
        }
        long previousRevision = reader.currentRevision();
        assertSuccess(exchange(HttpMethod.POST, LIKE_REQUEST, null));
        long revision = reader.currentRevision();
        assertThat(revision).isEqualTo(previousRevision + 1);
        assertThat(redis.hasKey(key(previousRevision))).isTrue();
        assertThat(redis.hasKey(key(revision))).isFalse();

        var workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        List<java.util.concurrent.Future<Sample>> futures = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        List<RequestSqlProbe.Event> sql;
        List<RequestStageProbe.Event> stages;
        long origin = System.nanoTime();
        probe.start();
        STAGES.start();
        try {
            for (int index = 0; index < 8; index++) {
                String id = "read-burst-" + index;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return sample(id, HttpMethod.GET, origin, 0);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            // 클라이언트 출발만 맞춘다. 서버 miss/DB 계산에 barrier나 sleep을 주입하지 않는다.
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            for (var future : futures) {
                samples.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            boolean stopped;
            try {
                stopped = workers.awaitTermination(12, TimeUnit.SECONDS);
            } finally {
                sql = probe.stop();
                stages = STAGES.stop();
            }
            assertThat(stopped).isTrue();
        }
        assertThat(samples).hasSize(8).allSatisfy(sample -> {
            assertThat(sample.status()).isEqualTo(200);
            assertThat(sample.valid()).isTrue();
            assertThat(sample.responseState()).isEqualTo("liked");
        });
        if (mode.singleFlightEnabled) {
            // 고정된 새 버전만 읽는 장면이다. 계속 쓰는 혼합 부하에 버전당 1회 조건을 일반화하지 않는다.
            assertThat(stages.stream().filter(event -> event.stage().equals("db.snapshot")).count()).isEqualTo(1);
            assertThat(stages.stream().filter(event -> event.stage().equals("cache.store")).count()).isEqualTo(1);
            // 느린 환경의 정상적인 500ms 대기 초과는 실패가 아니다. 우회도 실제 집계 비용에 포함한다.
            long directReads = stages.stream().filter(event -> event.stage().equals("db.direct")).count();
            assertThat(sql.stream().filter(event -> aggregate(event.sql())).count()).isEqualTo(1 + directReads);
        }
        assertThat(reader.currentRevision()).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like` WHERE user_id=1 AND product_id=100997", Long.class))
            .isEqualTo(1);
        assertThat(assertSuccess(exchange(HttpMethod.GET, REQUEST, null))).isEqualTo(liked);
        return Map.of("kind", "cold-version-client-burst", "repeat", repeat, "readers", 8, "revision", revision,
            "serverBarrierInjected", false, "reads", summary(samples),
            "mode", mode.name(), "cacheEnabled", true, "singleFlightEnabled", mode.singleFlightEnabled,
            "stageDiagnostics", diagnostics(samples, stages, sql, mode, revision, true));
    }

    private void configure(Mode mode) {
        doReturn(mode.cacheEnabled).when(cache).isEnabled();
        doReturn(mode.singleFlightEnabled).when(singleFlight).isEnabled();
    }

    private Map<String, Object> diagnostics(List<Sample> samples, List<RequestStageProbe.Event> stages,
                                            List<RequestSqlProbe.Event> sql, Mode mode,
                                            long revisionBefore, boolean initiallyLiked) {
        assertThat(stages).isNotEmpty().allSatisfy(event -> {
            assertThat(event.success()).as(event.requestId() + ": " + event.stage()).isTrue();
            assertThat(event.endNanos()).isGreaterThanOrEqualTo(event.startNanos());
        });
        var byRequest = stages.stream().collect(Collectors.groupingBy(RequestStageProbe.Event::requestId));
        var bySqlRequest = sql.stream().collect(Collectors.groupingBy(RequestSqlProbe.Event::requestId));
        var readSamples = samples.stream().filter(sample -> sample.id().startsWith("read-")).toList();
        assertThat(byRequest.keySet()).containsExactlyInAnyOrderElementsOf(readSamples.stream().map(Sample::id).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Sample sample : readSamples) {
            var events = byRequest.get(sample.id());
            assertThat(events.stream().map(RequestStageProbe.Event::stage).distinct().count()).isEqualTo(events.size());
            var eventMap = events.stream().collect(Collectors.toMap(RequestStageProbe.Event::stage, event -> event));
            assertThat(eventMap).containsKey("query.total");
            String outcome = mode.cacheEnabled ? eventMap.get("cache.lookup").outcome() : "off";
            ReadPath path = classify(mode, eventMap);
            long returnedRevision = path.returnedRevision();
            if (returnedRevision >= 0) {
                boolean expectedLiked = (returnedRevision - revisionBefore) % 2 == 0 ? initiallyLiked : !initiallyLiked;
                assertThat(sample.responseState()).as("반환 버전과 전체 DTO fixture의 일치")
                    .isEqualTo(expectedLiked ? "liked" : "unchanged");
            }
            // 직접 DB fallback은 버전을 반환하지 않는다. 전체 DTO 허용 상태·최종 sentinel 검사는 계속 유지한다.
            var queries = bySqlRequest.get(sample.id());
            assertThat(queries).allSatisfy(query -> assertThat(query.success()).isTrue());
            long snapshots = eventMap.containsKey("db.snapshot") ? 1 : 0;
            long directReads = eventMap.containsKey("db.direct") ? 1 : 0;
            assertThat(queries.stream().filter(query -> aggregate(query.sql())).count()).isEqualTo(snapshots + directReads);
            assertThat(queries.stream().filter(query -> count(query.sql())).count()).isEqualTo(snapshots + directReads);
            assertThat(queries.stream().filter(query -> detail(query.sql())).count()).isEqualTo(snapshots + directReads);
            assertThat(queries.stream().filter(query -> query.sql().toLowerCase(Locale.ROOT).startsWith("select ")).count())
                .isEqualTo((mode.cacheEnabled ? 1 : 0) + 4 * snapshots + 3 * directReads);
            Map<String, Long> times = new LinkedHashMap<>();
            eventMap.forEach((stage, event) -> times.put(stage, event.elapsedNanos()));
            // single-flight는 leader의 recheck/snapshot/store 또는 follower의 대기를 포함한다.
            // 이 중첩 자식 구간을 application 순차 시간에 다시 더하지 않는다.
            long children = events.stream().filter(event -> !event.stage().equals("query.total"))
                .filter(event -> !eventMap.containsKey("single-flight") || !List.of("cache.recheck", "db.snapshot", "cache.store").contains(event.stage()))
                .mapToLong(RequestStageProbe.Event::elapsedNanos).sum();
            times.put("query.unattributed", times.get("query.total") - children);
            if (eventMap.containsKey("single-flight")) {
                long nested = events.stream().filter(event -> List.of("cache.recheck", "db.snapshot", "cache.store").contains(event.stage()))
                    .mapToLong(RequestStageProbe.Event::elapsedNanos).sum();
                times.put("single-flight.overhead-or-wait", times.get("single-flight") - nested);
            }
            times.put("http.total", sample.httpNanos());
            times.put("http.outside-query", sample.httpNanos() - times.get("query.total"));
            times.put("sql.aggregate", queries.stream().filter(query -> aggregate(query.sql()))
                .mapToLong(RequestSqlProbe.Event::executeNanos).sum());
            var versionQueries = queries.stream().filter(query -> query.sql().toLowerCase(Locale.ROOT).contains("from product_list_revision")).toList();
            assertThat(versionQueries).hasSize((mode.cacheEnabled ? 1 : 0) + (int) snapshots);
            long initialSql = mode.cacheEnabled ? versionQueries.getFirst().executeNanos() : 0;
            times.put("sql.lookup-revision", initialSql);
            times.put("sql.snapshot-revision", versionQueries.stream().mapToLong(RequestSqlProbe.Event::executeNanos).sum() - initialSql);
            times.put("sql.count", queries.stream().filter(query -> count(query.sql()))
                .mapToLong(RequestSqlProbe.Event::executeNanos).sum());
            times.put("sql.detail", queries.stream().filter(query -> detail(query.sql()))
                .mapToLong(RequestSqlProbe.Event::executeNanos).sum());
            if (snapshots + directReads > 0) {
                long dbTime = times.get(snapshots > 0 ? "db.snapshot" : "db.direct");
                times.put("db.outside-jdbc-execute", dbTime - times.get("sql.aggregate") - times.get("sql.count")
                    - times.get("sql.detail") - times.get("sql.snapshot-revision"));
            }
            assertThat(times.values()).allSatisfy(time -> assertThat(time).isNotNegative());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestId", sample.id());
            row.put("outcome", outcome);
            row.put("branch", path.branch());
            row.put("returnedRevision", returnedRevision);
            row.put("returnedRevisionVerified", returnedRevision >= 0);
            row.put("responseState", sample.responseState());
            row.put("nanos", times);
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", rows);
        result.put("branches", rows.stream().collect(Collectors.groupingBy(row -> row.get("branch"),
            LinkedHashMap::new, Collectors.counting())));
        result.put("events", stages);
        result.put("fillsBySnapshotRevision", fillsByRevision(stages));
        result.put("lookupToSnapshotRevisionChanges", stages.stream().filter(event -> event.stage().equals("db.snapshot"))
            .filter(event -> !event.resultRevision().equals(byRequest.get(event.requestId()).stream()
                .filter(candidate -> candidate.stage().equals("cache.lookup")).findFirst().orElseThrow().inputRevision())).count());
        return result;
    }

    private ReadPath classify(Mode mode, Map<String, RequestStageProbe.Event> events) {
        var total = events.get("query.total");
        events.values().forEach(event -> {
            assertThat(event.startNanos()).isGreaterThanOrEqualTo(total.startNanos());
            assertThat(event.endNanos()).isLessThanOrEqualTo(total.endNanos());
        });
        if (!mode.cacheEnabled) {
            assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.direct");
            return new ReadPath("off", -1);
        }
        var lookup = events.get("cache.lookup");
        var initialRevision = events.get("db.lookup-revision");
        assertThat(initialRevision.resultRevision()).isEqualTo(lookup.inputRevision());
        assertThat(initialRevision.endNanos()).isLessThanOrEqualTo(lookup.startNanos());
        if (lookup.outcome().equals("hit")) {
            assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.lookup-revision", "cache.lookup");
            assertThat(lookup.resultRevision()).isEqualTo(lookup.inputRevision());
            return new ReadPath("initial-hit", lookup.resultRevision());
        }
        assertThat(lookup.outcome()).isEqualTo("miss");
        if (!mode.singleFlightEnabled) {
            assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.lookup-revision",
                "cache.lookup", "db.snapshot", "cache.store");
            assertThat(lookup.endNanos()).isLessThanOrEqualTo(events.get("db.snapshot").startNanos());
            verifySnapshotAndStore(events, lookup.inputRevision());
            return new ReadPath("off-coalescing", events.get("db.snapshot").resultRevision());
        }
        var flight = events.get("single-flight");
        assertThat(flight).isNotNull();
        assertThat(flight.inputRevision()).isEqualTo(lookup.inputRevision());
        assertThat(lookup.endNanos()).isLessThanOrEqualTo(flight.startNanos());
        if (flight.outcome().equals("fallback")) {
            assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.lookup-revision", "cache.lookup",
                "single-flight", "db.direct");
            assertThat(flight.resultRevision()).isNull();
            assertThat(flight.endNanos()).isLessThanOrEqualTo(events.get("db.direct").startNanos());
            return new ReadPath("wait-or-capacity-fallback", -1);
        }
        assertThat(flight.outcome()).isEqualTo("snapshot");
        assertThat(flight.resultRevision()).isGreaterThanOrEqualTo(lookup.inputRevision());
        if (events.containsKey("cache.recheck")) {
            var recheck = events.get("cache.recheck");
            assertThat(recheck.inputRevision()).isEqualTo(lookup.inputRevision());
            assertThat(recheck.startNanos()).isGreaterThanOrEqualTo(flight.startNanos());
            assertThat(recheck.endNanos()).isLessThanOrEqualTo(flight.endNanos());
            if (recheck.outcome().equals("hit")) {
                assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.lookup-revision", "cache.lookup",
                    "single-flight", "cache.recheck");
                assertThat(flight.resultRevision()).isEqualTo(recheck.resultRevision()).isEqualTo(lookup.inputRevision());
                return new ReadPath("recheck-hit", flight.resultRevision());
            }
            assertThat(recheck.outcome()).isEqualTo("miss");
            assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.lookup-revision", "cache.lookup",
                "single-flight", "cache.recheck", "db.snapshot", "cache.store");
            assertThat(recheck.endNanos()).isLessThanOrEqualTo(events.get("db.snapshot").startNanos());
            verifySnapshotAndStore(events, lookup.inputRevision());
            assertThat(flight.resultRevision()).isEqualTo(events.get("db.snapshot").resultRevision());
            assertThat(events.get("cache.store").endNanos()).isLessThanOrEqualTo(flight.endNanos());
            return new ReadPath("leader-load", flight.resultRevision());
        }
        assertThat(events.keySet()).containsExactlyInAnyOrder("query.total", "db.lookup-revision", "cache.lookup", "single-flight");
        return new ReadPath("shared", flight.resultRevision());
    }

    private void verifySnapshotAndStore(Map<String, RequestStageProbe.Event> events, long lookupRevision) {
        var snapshot = events.get("db.snapshot");
        var store = events.get("cache.store");
        assertThat(snapshot.resultRevision()).isGreaterThanOrEqualTo(lookupRevision);
        assertThat(store.inputRevision()).isEqualTo(snapshot.resultRevision());
        assertThat(snapshot.endNanos()).isLessThanOrEqualTo(store.startNanos());
    }

    private List<Map<String, Object>> fillsByRevision(List<RequestStageProbe.Event> stages) {
        var snapshots = stages.stream().filter(event -> event.stage().equals("db.snapshot"))
            .collect(Collectors.groupingBy(RequestStageProbe.Event::resultRevision));
        var stores = stages.stream().filter(event -> event.stage().equals("cache.store"))
            .collect(Collectors.toMap(RequestStageProbe.Event::requestId, event -> event));
        List<Map<String, Object>> groups = new ArrayList<>();
        snapshots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var ordered = entry.getValue().stream().sorted(Comparator.comparingLong(RequestStageProbe.Event::startNanos)).toList();
            int overlappingSnapshots = 0;
            int overlappingFills = 0;
            int startsAfterCompletedStore = 0;
            long priorSnapshotEnd = Long.MIN_VALUE;
            long priorStoreEnd = Long.MIN_VALUE;
            long firstStoreEnd = Long.MAX_VALUE;
            for (var event : ordered) {
                if (event.startNanos() < priorSnapshotEnd) {
                    overlappingSnapshots++;
                }
                if (event.startNanos() < priorStoreEnd) {
                    overlappingFills++;
                }
                if (event.startNanos() >= firstStoreEnd) {
                    startsAfterCompletedStore++;
                }
                priorSnapshotEnd = Math.max(priorSnapshotEnd, event.endNanos());
                long storeEnd = stores.get(event.requestId()).endNanos();
                priorStoreEnd = Math.max(priorStoreEnd, storeEnd);
                firstStoreEnd = Math.min(firstStoreEnd, storeEnd);
            }
            groups.add(Map.of("snapshotRevision", entry.getKey(), "fills", ordered.size(),
                "extraFillsBeyondOne", ordered.size() - 1, "overlappingSnapshotStarts", overlappingSnapshots,
                "overlappingFillStarts", overlappingFills, "snapshotStartsAfterCompletedStore", startsAfterCompletedStore,
                "requestIds", ordered.stream().map(RequestStageProbe.Event::requestId).toList()));
        });
        return groups;
    }

    private Sample sample(String id, HttpMethod method, long origin, long offset) {
        long start = System.nanoTime();
        try {
            var response = exchange(method, method == HttpMethod.GET ? REQUEST : LIKE_REQUEST, id);
            long elapsed = System.nanoTime() - start;
            JsonNode body = response.getBody();
            JsonNode data = body == null ? mapper.nullNode() : body.path("data");
            boolean valid = body != null && body.path("meta").path("result").asText().equals("SUCCESS");
            valid &= method == HttpMethod.GET ? data.equals(unchanged) || data.equals(liked)
                : data.path("productId").asLong() == 100997 && data.path("liked").asBoolean() == (method == HttpMethod.POST)
                    && data.path("likeCount").asInt() == (method == HttpMethod.POST ? 201 : 200);
            return new Sample(id, offset, start - origin, elapsed, response.getStatusCode().value(), valid, "",
                method == HttpMethod.GET ? data.equals(liked) ? "liked" : "unchanged" : "write");
        } catch (RuntimeException exception) {
            return new Sample(id, offset, start - origin, System.nanoTime() - start, 0, false, exception.toString(), "error");
        }
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String id) {
        HttpHeaders headers = new HttpHeaders();
        if (id != null) {
            headers.set(REQUEST_ID, id);
        }
        if (method != HttpMethod.GET) {
            headers.set("X-USER-ID", "alice");
        }
        return http.exchange(path, method, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode assertSuccess(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private Map<String, Long> counters() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String label : List.of("lookup.hit", "lookup.miss", "lookup.invalid", "lookup.error", "store.success", "store.error")) {
            String[] parts = label.split("\\.");
            counts.put(label, (long) meters.get("commerce.product.list.cache")
                .tags("operation", parts[0], "outcome", parts[1]).counter().count());
        }
        return counts;
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

    private static Map<String, Object> summary(List<Sample> samples) {
        return Map.of("count", samples.size(), "httpMillis", timings(samples, Sample::httpNanos),
            "scheduledToCompleteMillis", timings(samples, sample -> sample.startOffsetNanos() + sample.httpNanos() - sample.scheduledOffsetNanos()),
            "startDelayMillis", timings(samples, sample -> sample.startOffsetNanos() - sample.scheduledOffsetNanos()));
    }

    private static Map<String, Double> timings(List<Sample> samples, ToLongFunction<Sample> selector) {
        return distribution(samples.stream().mapToLong(selector).boxed().toList());
    }

    private static Map<String, Double> distribution(List<Long> nanos) {
        if (nanos.isEmpty()) {
            return Map.of();
        }
        var sorted = nanos.stream().sorted().toList();
        return Map.of("p50", sorted.get((int) Math.ceil(sorted.size() * 0.5) - 1) / 1_000_000.0,
            "p95", sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000.0,
            "max", sorted.getLast() / 1_000_000.0);
    }

    private static boolean aggregate(String sql) {
        return sql.contains("from product ") && sql.contains("`like`") && sql.contains(" group by ");
    }

    private static boolean count(String sql) {
        return sql.startsWith("select count(") && sql.contains("from product ") && !sql.contains("`like`");
    }

    private static boolean detail(String sql) {
        return sql.contains("from product ") && sql.contains(" in (")
            && !sql.contains("`like`") && !sql.contains(" group by ") && !count(sql);
    }

    private static boolean revisionUpdate(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        return lower.startsWith("insert into product_list_revision") || lower.startsWith("insert into `product_list_revision`");
    }

    private static String key(long revision) {
        return NAMESPACE + ":brand=all:sort=likes_desc:page=0:size=20:rev=" + revision;
    }

    record Sample(String id, long scheduledOffsetNanos, long startOffsetNanos, long httpNanos,
                  int status, boolean valid, String error, String responseState) { }

    record ReadPath(String branch, long returnedRevision) { }

    enum Mode {
        DATABASE(false, false), CACHE_ONLY(true, false), COALESCED(true, true);

        private final boolean cacheEnabled;
        private final boolean singleFlightEnabled;

        Mode(boolean cacheEnabled, boolean singleFlightEnabled) {
            this.cacheEnabled = cacheEnabled;
            this.singleFlightEnabled = singleFlightEnabled;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DiagnosticsConfiguration {
        @Bean
        static BeanPostProcessor stageObservation() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof ProductListReader) && !(bean instanceof ProductQueryService)
                        && !(bean instanceof ProductListSingleFlight)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        String stage = switch (invocation.getMethod().getName()) {
                            case "getList" -> "query.total";
                            case "currentRevision" -> "db.lookup-revision";
                            case "readSnapshot" -> "db.snapshot";
                            case "readList" -> "db.direct";
                            case "load" -> "single-flight";
                            default -> null;
                        };
                        if (stage == null) {
                            return invocation.proceed();
                        }
                        if (stage.equals("single-flight")) {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            boolean previous = IN_SINGLE_FLIGHT.get();
                            IN_SINGLE_FLIGHT.set(true);
                            try {
                                return STAGES.measure(stage, (Long) invocation.getArguments()[0],
                                    () -> (Optional<?>) invocation.proceed(),
                                    result -> result.map(snapshot -> ((ProductListSnapshot) snapshot).revision()).orElse(null),
                                    result -> result.isPresent() ? "snapshot" : "fallback");
                            } finally {
                                if (previous) {
                                    IN_SINGLE_FLIGHT.set(true);
                                } else {
                                    IN_SINGLE_FLIGHT.remove();
                                }
                            }
                        }
                        if (requestId() != null && bean instanceof ProductListReader) {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .as("reader 계측은 실제 트랜잭션 프록시 바깥에서 시작한다").isFalse();
                        }
                        return STAGES.measure(stage, null, invocation::proceed,
                            result -> result instanceof Long revision ? revision
                                : result instanceof ProductListSnapshot snapshot ? snapshot.revision() : null,
                            result -> "ok");
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    static final class ContainerExclusion extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getClassMetadata().getClassName().startsWith("com.loopers.testcontainers.");
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
