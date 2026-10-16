package com.loopers.config.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.interfaces.ProductReadFacade;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.cache.WriteInvalidation;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adversarial reproduction success is reported separately from consistency compliance. */
@SpringBootTest(properties = {"catalog.cache.enabled=true", "experiment.catalog-write-invalidate.enabled=true", "experiment.latest-first-ttl-seconds=5"})
@ActiveProfiles({"test", "experiment"})
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class WriteInvalidateExperimentTest {
    @DynamicPropertySource static void redisProperties(DynamicPropertyRegistry r) { ReadCacheHttpTest.properties(r); }
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @Autowired ProductReadFacade facade;
    @MockitoSpyBean ProductUseCase products;
    @MockitoSpyBean WriteInvalidation invalidation;
    @MockitoSpyBean org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;
    static final java.util.List<Map<String, Object>> RESULTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    final ObjectMapper json = new ObjectMapper();
    CommerceFixture fixture;
    com.loopers.user.domain.User user;
    com.loopers.product.domain.Product product;

    @BeforeEach void setup() {
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        fixture = new CommerceFixture(em, tm); user = fixture.user();
        product = fixture.product(fixture.brand("invalidate-brand"), "before", 100L, 10);
        clearInvocations(invalidation, products, jdbc);
    }
    @AfterEach void cleanup() { fixture.truncateRemainingTables(); }
    @AfterAll static void saveEvidence() throws Exception {
        var path = Path.of("build/write-invalidate-consistency.json"); Files.createDirectories(path.getParent());
        Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));
    }
    String key() { return CacheKeys.PRODUCTS + "::" + CacheKeys.product(product.getId()); }
    String detail() throws Exception {
        return mvc.perform(get("/api/v1/products/" + product.getId()).with(customer(user)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    String name(String body) throws Exception { return json.readTree(body).path("data").path("name").asText(); }
    void update() throws Exception {
        mvc.perform(put("/api-admin/v1/products/" + product.getId()).with(admin()).with(csrf())
            .contentType("application/json").content("{\"name\":\"after\",\"price\":300}"))
            .andExpect(status().isOk());
    }
    void remove() throws Exception {
        mvc.perform(delete("/api-admin/v1/products/" + product.getId()).with(admin()).with(csrf()))
            .andExpect(status().isOk());
    }
    void record(String scenario, boolean compliant, String observed) {
        RESULTS.add(Map.of("scenario", scenario, "consistencyCriterionPassed", compliant, "observed", observed));
    }
    void expireBasic() throws Exception {
        assertThat(redis.expire(key(), Duration.ofMillis(100))).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(key())) && System.nanoTime() < end) Thread.sleep(20);
        assertThat(redis.hasKey(key())).isFalse();
    }

    /** 준비: A·B 재고를 캐시한다. 실행: A를 품절로 만든다. 확인: A만 삭제·재조회하고 B의 TTL은 유지한다. */
    @Test @DisplayName("Write Invalidate도 A 재고 변경 시 B 상품의 키를 삭제하지 않는다")
    void stockInvalidationDeletesOnlyChangedProduct() throws Exception {
        var b = fixture.product(fixture.brand("other-brand"), "B", 100L, 1);
        var ids = java.util.List.of(product.getId(), b.getId());
        facade.stocks(ids);
        String aKey = CacheKeys.STOCKS + "::" + CacheKeys.item(CacheKeys.STOCKS, product.getId());
        String bKey = CacheKeys.STOCKS + "::" + CacheKeys.item(CacheKeys.STOCKS, b.getId());
        redis.expire(bKey, Duration.ofSeconds(120));
        mvc.perform(put("/api-admin/v1/products/" + product.getId() + "/stock").with(admin()).with(csrf())
            .contentType("application/json").content("{\"quantity\":0}")).andExpect(status().isOk());
        assertThat(redis.hasKey(aKey)).isFalse();
        assertThat(redis.hasKey(bKey)).isTrue();
        clearInvocations(products);
        assertThat(facade.stocks(ids).data()).contains(new ProductUseCase.StockStatus(product.getId(), true), new ProductUseCase.StockStatus(b.getId(), false));
        verify(products).findStockStatuses(java.util.List.of(product.getId()));
        assertThat(redis.getExpire(bKey)).isBetween(110L, 120L);
    }

    @Test @DisplayName("정상 수정은 커밋 뒤 삭제하고 hit은 revision SQL 없이 새 값을 반환한다")
    void normalUpdateAndHit() throws Exception {
        detail(); assertThat(key()).startsWith("products::v4:").doesNotContain(":rev=");
        update(); assertThat(redis.hasKey(key())).isFalse(); assertThat(name(detail())).isEqualTo("after");
        clearInvocations(jdbc, products);
        facade.detail(product.getId()); verifyNoInteractions(jdbc, products);
        mvc.perform(get("/api/v1/products/" + product.getId())).andExpect(status().isUnauthorized());
        record("normal-update-hit", true, "new value; no revision SQL on facade hit; unauthenticated HTTP rejected");
    }
    @Test @DisplayName("정상 삭제 완료 후 새 고객 조회는 404다")
    void normalDelete() throws Exception {
        detail(); remove(); assertThat(redis.hasKey(key())).isFalse();
        mvc.perform(get("/api/v1/products/" + product.getId()).with(customer(user))).andExpect(status().isNotFound());
        record("normal-delete", true, "404 after completed invalidation");
    }
    @Test @DisplayName("수정 롤백에는 캐시를 삭제하지 않고 기존 값을 유지한다")
    void rollback() throws Exception {
        String before = detail(); String cached = redis.opsForValue().get(key()); clearInvocations(invalidation);
        new TransactionTemplate(tm).executeWithoutResult(tx -> { products.update(product.getId(), "rolled-back", 900, null); tx.setRollbackOnly(); });
        verify(invalidation, never()).deletePattern(anyString());
        assertThat(redis.opsForValue().get(key())).isEqualTo(cached); assertThat(detail()).isEqualTo(before);
        record("rollback", true, "no deletion; unchanged DB/cache response");
    }
    @Test @DisplayName("TTL은 hit에 연장되지 않으며 만료 후 원본을 다시 읽는다")
    void expiry() throws Exception {
        detail(); assertThat(redis.getExpire(key())).isBetween(269L, 330L);
        redis.expire(key(), Duration.ofSeconds(2)); detail(); assertThat(redis.getExpire(key())).isBetween(0L, 2L);
        expireBasic(); clearInvocations(products); detail(); verify(products).findBasicProduct(product.getId());
        record("ttl", true, "base TTL 270..330s; hit nonrenewal; accelerated remaining TTL 100ms -> refill");
    }
    @ParameterizedTest @ValueSource(strings = {"update", "delete"})
    @DisplayName("[한계 재현] 삭제 실패는 옛 응답을 남기며 TTL 만료 후 회복한다")
    void deletionFailure(String action) throws Exception {
        detail(); double before = failureCount();
        doThrow(new RedisConnectionFailureException("injected invalidation failure")).when(invalidation).deletePattern(anyString());
        if (action.equals("update")) update(); else remove();
        assertThat(failureCount()).isGreaterThan(before);
        assertThat(name(detail())).isEqualTo("before");
        record("invalidation-failure-" + action, false, "DB write committed; new HTTP read returned old product with 200");
        expireBasic();
        if (action.equals("update")) assertThat(name(detail())).isEqualTo("after");
        else mvc.perform(get("/api/v1/products/" + product.getId()).with(customer(user))).andExpect(status().isNotFound());
        record("invalidation-failure-" + action + "-expiry-recovery", true, "fresh/404 after accelerated remaining TTL expiry");
    }
    double failureCount() {
        var counter = metrics.find("catalog.cache.invalidation").tag("outcome", "failure").counter();
        return counter == null ? 0 : counter.count();
    }
    @ParameterizedTest @ValueSource(strings = {"update", "delete"})
    @DisplayName("[한계 재현] 삭제가 성공해도 옛 조회가 늦게 캐시를 다시 채울 수 있다")
    void lateFill(String action) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1); CountDownLatch resume = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod(); loaded.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("reader not released");
            return result;
        }).when(products).findBasicProduct(product.getId());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var reader = executor.submit(this::detail);
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            if (action.equals("update")) update(); else remove();
            assertThat(redis.hasKey(key())).isFalse(); resume.countDown();
            // The old reader has an established DB snapshot and may finish with its old value.
            assertThat(name(reader.get(10, TimeUnit.SECONDS))).isEqualTo("before");
            assertThat(name(detail())).isEqualTo("before");
            record("late-fill-" + action, false, "successful deletion followed by old snapshot repopulation; new HTTP read returned old product");
            expireBasic();
            if (action.equals("update")) assertThat(name(detail())).isEqualTo("after");
            else mvc.perform(get("/api/v1/products/" + product.getId()).with(customer(user))).andExpect(status().isNotFound());
            record("late-fill-" + action + "-expiry-recovery", true, "fresh/404 after accelerated remaining TTL expiry");
        } finally { resume.countDown(); executor.shutdownNow(); }
    }
}
