package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "management.server.port=0")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@org.springframework.test.context.TestExecutionListeners(listeners = PaymentHttpTestSupport.ResourceClosureListener.class,
    mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Import({PaymentTxTestConfig.class, PaymentHttpTestSupport.TrackingConfiguration.class})
public abstract class PaymentHttpTestSupport extends PgTestSupport {
    @Autowired protected TestRestTemplate http;
    @Autowired protected OrderFacade orders;
    @Autowired protected ProductFixture products;
    @Autowired protected UserFixture users;
    @Autowired protected PointFacade points;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ControllableProbe probe;
    @Autowired protected Environment environment;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private com.loopers.fixture.ProductDetailCacheCleanUp cacheCleanup;
    @LocalServerPort protected int port;
    protected Long user;
    protected ProductModel product;
    protected Long order;
    @Autowired private RequestTracker requests;
    private ExecutorService workers;
    private final List<Future<?>> tasks = new ArrayList<>();

    @BeforeEach
    void prepareHttpFlow() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        http.getRestTemplate().setRequestFactory(factory);
        workers = Executors.newFixedThreadPool(12);
        cacheCleanup.deleteAll();
        user = users.createUserWithPoint().getId();
        points.charge(user, 10000L);
        product = products.createProduct("PG 복구 상품", 2000L, 5L);
        order = draft(product);
        assertThat(environment.getProperty("commerce.pg.callback-url"))
            .isEqualTo("http://127.0.0.1:" + port + "/api/v1/payments/callback");
    }

    @AfterEach
    void finishHttpFlow() throws InterruptedException {
        probe.clear();
        tasks.stream().filter(task -> !task.isDone()).forEach(task -> task.cancel(true));
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).as("HTTP 작업 종료").isTrue();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> requests.active.get() == 0);
        awaitPgIdle();
        cleanup.truncateAllTables();
        cacheCleanup.deleteAll();
    }

    protected <T> Future<T> async(Callable<T> work) {
        Future<T> task = workers.submit(work);
        tasks.add(task);
        return task;
    }

    protected Long draft(ProductModel item) {
        return orders.create(user, List.of(new OrderItemCommand(item.getId(), 2L))).id();
    }

    protected ResponseEntity<JsonNode> call(String path, HttpMethod method, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        return http.exchange("http://127.0.0.1:" + port + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> confirm(Long id) {
        return call("/api/v1/orders/" + id + "/confirm", HttpMethod.POST, PgCommands.forOrder(id));
    }

    protected ResponseEntity<JsonNode> reconcile(Long id) {
        return call("/api/v1/orders/" + id + "/payment/reconcile", HttpMethod.POST, null);
    }

    protected JsonNode detail(Long id) {
        ResponseEntity<JsonNode> response = call("/api/v1/orders/" + id, HttpMethod.GET, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("data");
    }

    protected ResponseEntity<JsonNode> callback(Long id, String key, String status) {
        return call("/api/v1/payments/callback", HttpMethod.POST,
            Map.of("providerOrderId", external(id), "transactionKey", key, "status", status));
    }

    protected String directPgCreate(Long id, long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "135135");
        var response = http.exchange(pg.baseUrl() + "/api/v1/payments", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderId", external(id), "amount", amount, "cardType", "TEST",
                "cardNo", "0000-0000-0000-0000", "callbackUrl", environment.getProperty("commerce.pg.callback-url")), headers),
            JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return response.getBody().path("transactionKey").asText();
    }

    protected String key(Long id) {
        return jdbc.queryForObject("SELECT transaction_key FROM payment WHERE order_id = ?", String.class, id);
    }

    protected Map<String, Object> payment(Long id) {
        return jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", id);
    }

    protected long stock(Long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, productId);
    }

    protected long histories(Long id, String cause) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = ?", Long.class, id, cause);
    }

    protected void noPointEffect() {
        assertThat(jdbc.queryForObject("SELECT balance FROM point WHERE user_id = ?", Long.class, user)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_history WHERE cause = 'ORDER_USE'", Long.class)).isZero();
    }

    protected static String external(Long id) { return PaymentModel.providerOrderIdOf(id); }

    protected static String transaction(String key, String external, long amount, String status) {
        return "{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + external + "\",\"amount\":" + amount
            + ",\"status\":\"" + status + "\"}";
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TrackingConfiguration {
        @org.springframework.context.annotation.Bean
        RequestTracker requestTracker() { return new RequestTracker(); }
    }

    static class RequestTracker extends org.springframework.web.filter.OncePerRequestFilter {
        final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            active.incrementAndGet();
            try { chain.doFilter(request, response); } finally { active.decrementAndGet(); }
        }
    }

    public static class ResourceClosureListener extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override
        public int getOrder() {
            return new org.springframework.test.context.support.DirtiesContextTestExecutionListener().getOrder() - 1;
        }

        @Override
        public void beforeTestMethod(org.springframework.test.context.TestContext context) {
            context.setAttribute("i5.pg", context.getApplicationContext().getBean(com.loopers.fixture.pg.PgFixtureServer.class));
            context.setAttribute("i5.dbPool", context.getApplicationContext().getBean(com.zaxxer.hikari.HikariDataSource.class));
        }

        @Override
        public void afterTestMethod(org.springframework.test.context.TestContext context) {
            var server = (com.loopers.fixture.pg.PgFixtureServer) context.getAttribute("i5.pg");
            var pool = (com.zaxxer.hikari.HikariDataSource) context.getAttribute("i5.dbPool");
            assertThat(server.isTerminated()).as("컨텍스트 종료 뒤 HTTP/콜백 worker 종료").isTrue();
            assertThat(pool.isClosed()).as("컨텍스트 종료 뒤 DB 풀 종료").isTrue();
        }
    }
}
