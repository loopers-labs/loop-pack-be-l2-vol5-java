package com.loopers.config.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in observation: stale values are measured as a limitation, never declared consistency success. */
@SpringBootTest(properties = {"catalog.cache.enabled=true", "experiment.catalog-write-invalidate.enabled=true",
    "experiment.latest-first-ttl-seconds=5"})
@ActiveProfiles({"test", "experiment"})
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@EnabledIfSystemProperty(named = "experiment.late-fill-observation", matches = "true")
class LateFillObservationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { ReadCacheHttpTest.properties(r); }
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;
    @MockitoSpyBean ProductUseCase products;
    final ObjectMapper json = new ObjectMapper();
    final Path evidence = Path.of("build/late-fill-observation");
    com.loopers.user.domain.User user;
    Long target;

    String key() { return CacheKeys.PRODUCTS + "::" + CacheKeys.products("latest", null, 0, 20); }
    int read() throws Exception {
        String body = mvc.perform(get("/api/v1/products").param("sort", "latest").param("page", "0").param("size", "20")
            .with(customer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var item : json.readTree(body).path("data").path("content")) {
            if (item.path("id").asLong() == target) return Integer.parseInt(item.path("name").asText().substring(1));
        }
        throw new IllegalStateException("target absent: " + body);
    }
    void write(int version) throws Exception {
        mvc.perform(put("/api-admin/v1/products/" + target).with(admin()).with(csrf())
            .contentType("application/json").content("{\"name\":\"v" + version + "\",\"price\":100}"))
            .andExpect(status().isOk());
    }
    void save(String file, Object value) throws Exception {
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(file), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    static double ms(long nanos) { return nanos / 1_000_000.0; }
    static void until(long deadline) throws InterruptedException {
        long left = deadline - System.nanoTime();
        if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
    }

    /** 준비: 실제 목록 캐시와 관리자 API. 실행: 저장 순서 제어 3회, 자연 혼합 3회. 확인: 빈도와 노출 시간을 원본으로 보전한다. */
    @Test @DisplayName("늦은 채움 재현 및 자연 혼합의 stale 응답·관찰 시간 측정")
    void observeControlledAndNaturalLateFill() throws Exception {
        var fixture = new CommerceFixture(em, tm);
        try {
            try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
            user = fixture.user();
            var brand = fixture.brand("late-fill-observation");
            for (int i = 0; i < 19; i++) fixture.product(brand, "other-" + i, 100L, 10);
            target = fixture.product(brand, "v0", 100L, 10).getId();
            assertThat(read()).isZero();
            assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
            for (int round = 1; round <= 3; round++) controlled(round);
            reset(products);
            write(1000); read(); // One warmup before all natural rounds; no restart or cache clear between rounds.
            var committed = new AtomicInteger(1000);
            for (int round = 1; round <= 3; round++) natural(round, committed);
        } finally { fixture.truncateRemainingTables(); }
    }

    void controlled(int round) throws Exception {
        int previous = round * 10;
        write(previous);
        var loaded = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.compareAndSet(true, false)) {
                loaded.countDown();
                if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("controlled reader timeout");
            }
            return result;
        }).when(products).findBasicPage(null, ProductSort.LATEST, 0, 20);
        var executor = Executors.newSingleThreadExecutor();
        var samples = new ArrayList<Map<String, Object>>();
        try {
            var oldReader = executor.submit(this::read);
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            write(previous + 1);
            long commitCompleted = System.nanoTime();
            assertThat(redis.hasKey(key())).isFalse();
            resume.countDown();
            assertThat(oldReader.get(10, TimeUnit.SECONDS)).isEqualTo(previous);
            long oldReaderCompleted = System.nanoTime();
            long initialTtl = redis.getExpire(key(), TimeUnit.MILLISECONDS);
            assertThat(initialTtl).isBetween(1L, 5000L);
            int actual;
            do {
                long start = System.nanoTime(); actual = read();
                samples.add(Map.of("startAfterCommitMs", ms(start - commitCompleted), "endAfterCommitMs", ms(System.nanoTime() - commitCompleted),
                    "actual", actual, "expected", previous + 1, "stale", actual < previous + 1));
                if (actual == previous + 1) break;
                assertThat(ms(System.nanoTime() - oldReaderCompleted)).isLessThan(10000);
                Thread.sleep(20);
            } while (true);
            assertThat(samples.stream().filter(x -> Boolean.TRUE.equals(x.get("stale"))).count()).isPositive();
            save("controlled-" + round + ".json", Map.of("round", round, "initialRemainingTtlMs", initialTtl,
                "oldReaderCompletedAfterCommitMs", ms(oldReaderCompleted - commitCompleted), "samples", samples));
        } finally { resume.countDown(); executor.shutdownNow(); reset(products); }
    }

    void natural(int round, AtomicInteger committed) throws Exception {
        var reads = new ConcurrentLinkedQueue<Map<String, Object>>();
        var writes = new ConcurrentLinkedQueue<Map<String, Object>>();
        var readers = Executors.newFixedThreadPool(32);
        var writer = Executors.newSingleThreadExecutor();
        var permits = new Semaphore(32);
        long origin = System.nanoTime();
        int dropped = 0;
        var writing = writer.submit(() -> {
            for (int i = 0; i < 60; i++) {
                until(origin + TimeUnit.SECONDS.toNanos(i) + TimeUnit.MILLISECONDS.toNanos(5));
                int next = committed.get() + 1;
                long start = System.nanoTime();
                try {
                    write(next); committed.set(next);
                    writes.add(Map.of("version", next, "startMs", ms(start - origin), "endMs", ms(System.nanoTime() - origin), "ok", true));
                } catch (Exception e) {
                    writes.add(Map.of("version", next, "startMs", ms(start - origin), "endMs", ms(System.nanoTime() - origin), "ok", false, "error", e.toString()));
                }
            }
            return null;
        });
        try {
            for (int i = 0; i < 6000; i++) {
                long due = origin + TimeUnit.MILLISECONDS.toNanos(i * 10L);
                until(due);
                if (System.nanoTime() - due > TimeUnit.MILLISECONDS.toNanos(10) || !permits.tryAcquire()) { dropped++; continue; }
                readers.submit(() -> {
                    long start = System.nanoTime(); int expected = committed.get();
                    try {
                        int actual = read();
                        reads.add(Map.of("startMs", ms(start - origin), "endMs", ms(System.nanoTime() - origin), "expected", expected,
                            "actual", actual, "stale", actual < expected, "ok", true));
                    } catch (Exception e) {
                        reads.add(Map.of("startMs", ms(start - origin), "endMs", ms(System.nanoTime() - origin), "expected", expected, "ok", false, "error", e.toString()));
                    } finally { permits.release(); }
                });
            }
            writing.get(30, TimeUnit.SECONDS);
            readers.shutdown(); assertThat(readers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            save("natural-" + round + ".json", Map.of("round", round, "scheduledReads", 6000, "droppedReads", dropped,
                "durationMs", ms(System.nanoTime() - origin), "reads", reads, "writes", writes));
            assertThat(reads.stream().filter(x -> !Boolean.TRUE.equals(x.get("ok"))).count()).isZero();
            assertThat(writes.stream().filter(x -> !Boolean.TRUE.equals(x.get("ok"))).count()).isZero();
        } finally { readers.shutdownNow(); writer.shutdownNow(); }
    }
}
