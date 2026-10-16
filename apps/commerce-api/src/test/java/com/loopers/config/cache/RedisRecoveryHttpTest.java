package com.loopers.config.cache;

import com.loopers.config.redis.RedisConfig;
import com.loopers.product.application.ProductUseCase;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"catalog.cache.enabled=true", "logging.level.io.lettuce.core.protocol.ConnectionWatchdog=DEBUG"})
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisRecoveryHttpTest {
    // This dedicated container is the application's only Redis endpoint in this test.
    static final int REDIS_PORT = availablePort();
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            com.github.dockerjava.api.model.PortBinding.parse(REDIS_PORT + ":6379")));
    static { REDIS.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }

    @Autowired com.loopers.support.cache.CacheVersions versions;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @Autowired MeterRegistry metrics;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clearOwnRedis() throws Exception {
        // Fixture maintenance uses the dedicated container, not the application's 100ms budget.
        // Actual HTTP/cache/reconnect assertions below still use the production client settings.
        var flushed = REDIS.execInContainer("redis-cli", "FLUSHDB", "SYNC");
        assertThat(flushed.getExitCode()).as("Fixture Redis flush exit code").isZero();
        assertThat(flushed.getStdout().trim()).as("Fixture Redis flush result").isEqualTo("OK");
        var size = REDIS.execInContainer("redis-cli", "DBSIZE");
        assertThat(size.getExitCode()).as("Fixture Redis size exit code").isZero();
        assertThat(size.getStdout().trim()).as("Fixture Redis starts empty").isEqualTo("0");
    }

    @AfterEach
    void restoreOwnRedis() throws Exception {
        // The current context is disposed after this test. Restore the server for the next
        // context; application reconnect assertions belong to the explicit recovery scenarios.
        if (!Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getRunning())) {
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        }
        for (int i = 0; i < 40; i++) {
            if (REDIS.execInContainer("redis-cli", "PING").getStdout().contains("PONG")) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Redis server cleanup did not finish");
    }
    @MockitoSpyBean ProductUseCase products;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;

    @AfterAll
    static void stopOwnContainer() { REDIS.stop(); }

    static int availablePort() {
        // Docker's random published port changes on stop/start; pin an unused test port.
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reserve Redis test port", e); }
    }

    /**
     * 준비: 실제 MySQL 상품 두 개를 조회하여 miss/hit 일치와 원본 호출 감소를 확인하고 Redis에 저장한다.
     * 실행: 해당 Redis 컨테이너를 중단하고 관리자 API로 가격 수정·다른 상품 삭제 후 고객 조회한다.
     * 확인: 장애 중 새 가격·404를 반환한다. 같은 컨테이너를 재시작해 옛 키가 실제 남아 있어도
     * 새 revision의 결과를 채우고 이후 hit에서 원본 조회가 줄어드는지 검사한다.
     * 정리: finally에서 DB fixture를 정리하고 AfterAll에서 전용 Redis만 제거한다.
     */
    @RepeatedTest(3)
    @Timeout(300)
    @DisplayName("실제 Redis 중단 중 수정·삭제는 DB로 조회하고 복구 후 옛 키를 무시하며 재채움한다")
    void outageWritesAndRecoveryNeverReuseOldRevision() throws Exception {
        var fixture = new CommerceFixture(em, tm);
        try {
            var user = fixture.user();
            var brand = fixture.brand("redis-recovery");
            var updated = fixture.product(brand, "before", 100L, 1);
            var deleted = fixture.product(brand, "delete-me", 200L, 1);
            String updateUrl = "/api/v1/products/" + updated.getId();
            String deleteUrl = "/api/v1/products/" + deleted.getId();
            String oldUpdateKey = key(updated.getId());
            String oldDeleteKey = key(deleted.getId());
            clearInvocations(products);
            String miss = mvc.perform(get(updateUrl).with(customer(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            String hit = mvc.perform(get(updateUrl).with(customer(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertThat(hit).isEqualTo(miss);
            verify(products, times(1)).findBasicProduct(updated.getId());
            mvc.perform(get(deleteUrl).with(customer(user))).andExpect(status().isOk());
            // Keep stale evidence beyond the outage: test fixture TTL only, not application policy.
            redis.expire(oldUpdateKey, Duration.ofMinutes(15));
            redis.expire(oldDeleteKey, Duration.ofMinutes(15));
            String oldValue = redis.opsForValue().get(oldUpdateKey);
            assertThat(REDIS.execInContainer("redis-cli", "SAVE").getStdout()).contains("OK");
            REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(2).exec();
            assertThat(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getRunning()).isFalse();

            double errorsBefore = errors("get");
            double putErrorsBefore = errors("put");
            mvc.perform(put("/api-admin/v1/products/" + updated.getId()).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"after\",\"price\":400}"))
                .andExpect(status().isOk());
            clearInvocations(products);
            long started = System.nanoTime();
            mvc.perform(get(updateUrl).with(customer(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("after")).andExpect(jsonPath("$.data.price").value(400));
            long updateMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            verify(products, times(1)).findBasicProduct(updated.getId());
            mvc.perform(delete("/api-admin/v1/products/" + deleted.getId()).with(admin()).with(csrf()))
                .andExpect(status().isOk());
            started = System.nanoTime();
            mvc.perform(get(deleteUrl).with(customer(user))).andExpect(status().isNotFound());
            long deleteMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(errors("get")).isGreaterThan(errorsBefore);
            assertThat(errors("put")).isGreaterThan(putErrorsBefore);
            System.out.printf("REDIS_RECOVERY outageUpdateMs=%d outageDeletedMs=%d getErrors=%.0f putErrors=%.0f%n",
                updateMillis, deleteMillis, errors("get") - errorsBefore, errors("put") - putErrorsBefore);

            // Regression guard for the former 60-second wait; not a load-test SLO.
            assertThat(updateMillis).as("Outage fallback does not wait for the old 60-second timeout").isLessThan(5000);
            assertThat(deleteMillis).as("Deleted lookup also falls back promptly").isLessThan(5000);

            // Docker start keeps the same container, mapped port, and saved Redis data.
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
            var binding = REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(new com.github.dockerjava.api.model.ExposedPort(6379));
            assertThat(binding[0].getHostPortSpec()).isEqualTo(String.valueOf(REDIS_PORT));
            boolean ready = false;
            for (int attempt = 0; attempt < 40; attempt++) {
                if (REDIS.execInContainer("redis-cli", "PING").getStdout().contains("PONG")) { ready = true; break; }
                Thread.sleep(250);
            }
            assertThat(ready).as("Redis restart readiness within 10 seconds").isTrue();
            // Server PONG does not mean Lettuce has reconnected its existing socket yet.
            long reconnectStarted = System.nanoTime();
            boolean reconnected = false;
            String reconnectFailure = "none";
            for (int attempt = 0; attempt < 40; attempt++) {
                try (var connection = redis.getConnectionFactory().getConnection()) {
                    if ("PONG".equals(connection.ping())) { reconnected = true; break; }
                } catch (org.springframework.dao.DataAccessException reconnecting) {
                    reconnectFailure = reconnecting.toString();
                    System.out.printf("REDIS_DIAG attempt=%d error=%s%n", attempt, reconnectFailure);
                }
                Thread.sleep(250);
            }
            System.out.printf("REDIS_DIAG recovered=%s lastError=%s server=%s%n", reconnected, reconnectFailure,
                REDIS.execInContainer("redis-cli", "PING").getStdout().trim());
            assertThat(reconnected).as("Application Redis connection automatically recovers").isTrue();
            System.out.printf("REDIS_RECOVERY reconnectMs=%d%n", Duration.ofNanos(System.nanoTime() - reconnectStarted).toMillis());
            assertThat(redis.opsForValue().get(oldUpdateKey)).isEqualTo(oldValue);
            assertThat(redis.hasKey(oldDeleteKey)).isTrue();
            String newKey = key(updated.getId());
            assertThat(newKey).isNotEqualTo(oldUpdateKey);
            assertThat(redis.hasKey(newKey)).isFalse();
            clearInvocations(products);
            double recoveredGetErrors = errors("get");
            double recoveredPutErrors = errors("put");
            mvc.perform(get(updateUrl).with(customer(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(400));
            assertThat(redis.opsForValue().get(newKey)).contains("after");
            assertThat(redis.getExpire(newKey)).isBetween(269L, 330L);
            mvc.perform(get(updateUrl).with(customer(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(400));
            verify(products, times(1)).findBasicProduct(updated.getId());
            mvc.perform(get(deleteUrl).with(customer(user))).andExpect(status().isNotFound());
            assertThat(errors("get")).isEqualTo(recoveredGetErrors);
            assertThat(errors("put")).isEqualTo(recoveredPutErrors);
            System.out.println("REDIS_RECOVERY staleKeysPreserved=true refill=true hitOriginalCalls=0 deletedStatus=404");
        } finally {
            fixture.truncateRemainingTables();
        }
    }

    /** 준비: Alice의 좋아요·주문을 조회한다. 실행: 장애/복구 후 Bob으로 접근한다.
     * 확인: 타인 좋아요·주문 조회/확정 거절, 본인 목록·잔액만 노출, 거절로 재고·잔액·주문 불변.
     */
    @ParameterizedTest(name = "사용자 경계: {0}")
    @ValueSource(strings = {"outage", "recovered"})
    @Timeout(300)
    @DisplayName("[R-LIKE-04/R-ORDER-13/R-POINT-02] Redis 장애·복구에도 사용자 소유권을 유지한다")
    void ownershipSurvivesOutageAndRecovery(String phase) throws Exception {
        var fixture = new CommerceFixture(em, tm);
        try {
            var alice = fixture.userWithPoint(1000);
            var bob = fixture.userWithPoint(200);
            var product = fixture.product(fixture.brand("owner"), "private-like", 100L, 2);
            mvc.perform(post("/api/v1/products/{id}/likes", product.getId()).with(customer(alice))).andExpect(status().isCreated());
            String likes = "/api/v1/users/" + alice.getId() + "/likes";
            mvc.perform(get(likes).with(customer(alice))).andExpect(status().isOk());
            long order = createOrder(alice, product.getId());
            mvc.perform(get("/api/v1/orders/{id}", order).with(customer(alice))).andExpect(status().isOk());
            stopRedis();
            if (phase.equals("recovered")) recover();
            mvc.perform(get(likes).with(customer(bob))).andExpect(failure(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
            mvc.perform(get("/api/v1/orders/{id}", order).with(customer(bob))).andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
            mvc.perform(post("/api/v1/orders/{id}/confirm", order).with(customer(bob))).andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
            mvc.perform(get(likes).with(customer(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(product.getId()));
            mvc.perform(get("/api/v1/users/{id}/likes", bob.getId()).with(customer(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(0));
            mvc.perform(get("/api/v1/orders").with(customer(bob))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(0));
            mvc.perform(get("/api/v1/orders/{id}", order).with(customer(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
            balance(alice, 1000); balance(bob, 200);
            assertThat(stock(product.getId())).isEqualTo(2);
        } finally { fixture.truncateRemainingTables(); }
    }

    /** 준비: 100원·재고 2를 캐시한다. 실행: 장애 중 400원으로 수정 후 주문 생성, 이후 가격을 900원으로 수정한다.
     * 확인: 장애/복구 후에도 주문 생성 시점 400원으로 결제하고 DB 재고·잔액을 차감한다.
     * 재고/포인트 부족은 각각 거절하며 주문 DRAFT와 재고·잔액을 보존한다.
     */
    @ParameterizedTest(name = "주문 판단: {0}")
    @ValueSource(strings = {"outage", "recovered"})
    @Timeout(300)
    @DisplayName("[R-ORDER-02/07~09] 캐시 장애·옛 표시 값은 주문 가격·재고·포인트 판단을 바꾸지 않는다")
    void orderUsesDatabaseAndStoredOrderPrice(String phase) throws Exception {
        var fixture = new CommerceFixture(em, tm);
        try {
            var alice = fixture.userWithPoint(1000);
            var poor = fixture.userWithPoint(1);
            var product = fixture.product(fixture.brand("order"), "item", 100L, 2);
            mvc.perform(get("/api/v1/products/{id}", product.getId()).with(customer(alice))).andExpect(status().isOk());
            mvc.perform(get("/api/v1/products/" + product.getId()).with(customer(alice))).andExpect(status().isOk());
            stopRedis();
            changePrice(product.getId(), 400);
            long order = createOrder(alice, product.getId());
            changePrice(product.getId(), 900);
            if (phase.equals("recovered")) recover();
            mvc.perform(post("/api/v1/orders/{id}/confirm", order).with(customer(alice))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(400)).andExpect(jsonPath("$.data.payment.amount").value(400))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(400));
            balance(alice, 600);
            assertThat(stock(product.getId())).isEqualTo(1);
            long insufficientPoint = createOrder(poor, product.getId());
            mvc.perform(post("/api/v1/orders/{id}/confirm", insufficientPoint).with(customer(poor)))
                .andExpect(failure(HttpStatus.CONFLICT, "INSUFFICIENT_POINT"));
            balance(poor, 1);
            assertThat(stock(product.getId())).isEqualTo(1);
            mvc.perform(get("/api/v1/orders/{id}", insufficientPoint).with(customer(poor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
            mvc.perform(put("/api-admin/v1/products/{id}/stock", product.getId()).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}")).andExpect(status().isOk());
            changePrice(product.getId(), 400);
            long insufficientStock = createOrder(alice, product.getId());
            mvc.perform(post("/api/v1/orders/{id}/confirm", insufficientStock).with(customer(alice)))
                .andExpect(failure(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK"));
            balance(alice, 600);
            assertThat(stock(product.getId())).isZero();
            mvc.perform(get("/api/v1/orders/{id}", insufficientStock).with(customer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        } finally { fixture.truncateRemainingTables(); }
    }

    /** 준비: 확정 주문과 브랜드 상품 목록·좋아요·일괄 응답을 저장한다. 실행: 장애 중 브랜드를 삭제한다.
     * 확인: 장애/복구 모두 소속 상품 조회·새 주문 제외, 다른 브랜드 유지, 과거 주문·결제액·잔액 보존.
     */
    @ParameterizedTest(name = "브랜드 삭제 보호: {0}")
    @ValueSource(strings = {"outage", "recovered"})
    @Timeout(300)
    @DisplayName("[R-ORDER-05/14] 장애 중 브랜드 삭제는 고객 노출·새 주문을 막고 과거 주문을 보존한다")
    void brandDeletionPreservesPastOrders(String phase) throws Exception {
        var fixture = new CommerceFixture(em, tm);
        try {
            var alice = fixture.userWithPoint(1000);
            var brand = fixture.brand("deleted-brand");
            var a = fixture.product(brand, "a", 100L, 2);
            var a2 = fixture.product(brand, "a2", 150L, 1);
            var b = fixture.product(fixture.brand("other-brand"), "b", 200L, 1);
            long order = createOrder(alice, a.getId());
            mvc.perform(post("/api/v1/orders/{id}/confirm", order).with(customer(alice))).andExpect(status().isOk());
            String past = mvc.perform(get("/api/v1/orders/{id}", order).with(customer(alice))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            mvc.perform(post("/api/v1/products/{id}/likes", a.getId()).with(customer(alice))).andExpect(status().isCreated());
            String ids = a.getId() + "," + a2.getId() + "," + b.getId();
            var paths = java.util.List.of("/api/v1/products?sort=latest", "/api/v1/products?sort=price_asc", "/api/v1/products?sort=likes_desc",
                "/api/v1/users/" + alice.getId() + "/likes");
            for (String path : paths) mvc.perform(get(path).with(customer(alice))).andExpect(status().isOk());
            stopRedis();
            mvc.perform(delete("/api-admin/v1/brands/{id}", brand.getId()).with(admin()).with(csrf())).andExpect(status().isOk());
            if (phase.equals("recovered")) recover();
            for (String path : paths) {
                var response = data(mvc.perform(get(path).with(customer(alice))).andExpect(status().isOk()).andReturn());
                if (path.contains("/users/")) assertThat(response.path("content").size()).isZero();
                else { assertThat(response.path("content").size()).isEqualTo(1); assertThat(response.path("content").get(0).path("id").asLong()).isEqualTo(b.getId()); }
            }
            for (Long id : java.util.List.of(a.getId(), a2.getId())) {
                mvc.perform(get("/api/v1/products/{id}", id).with(customer(alice))).andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
                mvc.perform(post("/api/v1/orders").with(customer(alice)).contentType(MediaType.APPLICATION_JSON).content(items(id)))
                    .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
            }
            String after = mvc.perform(get("/api/v1/orders/{id}", order).with(customer(alice))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertThat(after).isEqualTo(past);
            balance(alice, 900);
            assertThat(stock(a.getId())).isEqualTo(1);
            mvc.perform(get("/api/v1/orders").with(customer(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1));
        } finally { fixture.truncateRemainingTables(); }
    }

    void stopRedis() throws Exception {
        assertThat(REDIS.execInContainer("redis-cli", "SAVE").getStdout()).contains("OK");
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(2).exec();
        assertThat(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getRunning()).isFalse();
    }

    void recover() throws Exception {
        if (!Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getRunning())) {
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        }
        for (int i = 0; i < 40; i++) {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                if ("PONG".equals(connection.ping())) return;
            } catch (org.springframework.dao.DataAccessException reconnecting) { }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Redis automatic recovery did not finish");
    }

    long createOrder(com.loopers.user.domain.User user, Long productId) throws Exception {
        return data(mvc.perform(post("/api/v1/orders").with(customer(user)).contentType(MediaType.APPLICATION_JSON).content(items(productId)))
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    String items(Long id) { return "{\"items\":[{\"productId\":" + id + ",\"quantity\":1}]}"; }

    void changePrice(Long id, long price) throws Exception {
        mvc.perform(put("/api-admin/v1/products/{id}", id).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"item\",\"price\":" + price + "}")).andExpect(status().isOk());
    }

    void balance(com.loopers.user.domain.User user, long expected) throws Exception {
        mvc.perform(get("/api/v1/points").with(customer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(expected));
    }

    int stock(Long id) { return jdbc.queryForObject("select quantity from stock where product_id = ?", Integer.class, id); }

    String key(Long id) {
        return CacheKeys.PRODUCTS + "::" + versions.key(CacheKeys.product(id), CacheKeys.CATALOG_GROUP);
    }

    double errors(String operation) {
        var counter = metrics.find("catalog.cache.errors").tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }
}
