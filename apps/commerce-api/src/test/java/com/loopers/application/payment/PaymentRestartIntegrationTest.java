package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.CommerceApiApplication;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.PaymentStatus;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.infrastructure.payment.PgHttpProperties;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@org.junit.jupiter.api.Timeout(60)
class PaymentRestartIntegrationTest {
    private static final String SCHEMA = "pg_restart_recovery";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseAndPgOutliveTwoCommerceContextsWithoutResending(boolean transactionExists) throws Exception {
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        String originalUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String server = originalUrl.substring(0, originalUrl.lastIndexOf('/'));
        try (var root = new SingleConnectionDataSource(server, "root", "test", true)) {
            JdbcTemplate admin = new JdbcTemplate(root);
            admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            admin.execute("GRANT ALL ON " + SCHEMA + ".* TO 'test'@'%'");
            try (PgFixtureServer pg = PgFixtureServer.start()) {
                try {
                    Long user;
                    Long order;
                    Long product;
                    Map<String, Object> persisted;
                    List<Map<String, Object>> columns;
                    CircuitBreakerRegistry oldRegistry;
                    CloseableHttpClient oldClient;
                    HikariDataSource oldPool;
                    String callback;
                    RestTemplate http = client();
                    try (ConfigurableApplicationContext first = boot(server, pg, "create")) {
                        user = first.getBean(UserFixture.class).createUserWithoutPoint().getId();
                        ProductModel item = first.getBean(ProductFixture.class).createProduct("재시작 상품", 2000L, 5L);
                        product = item.getId();
                        JsonNode created = call(first, http, user, "/api/v1/orders", Map.of("items",
                            List.of(Map.of("productId", product, "quantity", 2))), HttpMethod.POST).getBody();
                        order = created.path("data").path("id").asLong();
                        if (transactionExists) {
                            pg.enqueuePlan(PgOperation.CREATE, external(order), ResponsePlan.closeEarly());
                            assertThat(call(first, http, user, "/api/v1/orders/" + order + "/confirm",
                                PgCommands.forOrder(order), HttpMethod.POST).getStatusCode().value()).isEqualTo(202);
                        } else {
                            first.getBean(PaymentTransactionService.class).beginConfirmation(user, order, PgCommands.forOrder(order));
                        }
                        JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
                        persisted = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order);
                        assertThat(persisted).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null);
                        assertThat(persisted.get("dispatch_claimed_at")).isNotNull();
                        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, product)).isEqualTo(3);
                        columns = columns(jdbc);
                        oldRegistry = first.getBean(CircuitBreakerRegistry.class);
                        oldRegistry.circuitBreaker("pgLookup").transitionToOpenState();
                        oldClient = first.getBean("pgHttpClient", CloseableHttpClient.class);
                        oldPool = first.getBean(HikariDataSource.class);
                        callback = first.getBean(PgHttpProperties.class).callbackUrl().toString();
                        await().atMost(Duration.ofSeconds(5)).until(() -> pg.state().activeRequests() == 0);
                    }
                    assertThat(oldPool.isClosed()).isTrue();
                    assertThat(pg.isTerminated()).isFalse();
                    var pgBefore = pg.state();
                    try (ConfigurableApplicationContext second = boot(server, pg, "validate")) {
                        System.out.println("[I5 callback URLs] first=" + callback + " second="
                            + second.getBean(PgHttpProperties.class).callbackUrl());
                        assertThat(second.getBean(CircuitBreakerRegistry.class)).isNotSameAs(oldRegistry);
                        assertThat(second.getBean("pgHttpClient", CloseableHttpClient.class)).isNotSameAs(oldClient);
                        assertThat(second.getBean(CircuitBreakerRegistry.class).circuitBreaker("pgLookup").getState())
                            .isEqualTo(CircuitBreaker.State.CLOSED);
                        JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
                        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order)).isEqualTo(persisted);
                        assertThat(columns(jdbc)).isEqualTo(columns);
                        assertThat(pg.state()).isEqualTo(pgBefore);
                        JsonNode stored = call(second, http, user, "/api/v1/orders/" + order, null, HttpMethod.GET).getBody().path("data");
                        assertThat(stored.path("status").asText()).isEqualTo("PAYMENT_PENDING");
                        assertThat(stored.path("payment").path("status").asText()).isEqualTo("UNKNOWN");
                        if (transactionExists) {
                            pg.setStatus(pg.state().transactions().getFirst().transactionKey(), PaymentStatus.SUCCESS);
                        }
                        var result = call(second, http, user, "/api/v1/orders/" + order + "/payment/reconcile", null, HttpMethod.POST);
                        assertThat(result.getStatusCode().value()).isEqualTo(transactionExists ? 200 : 202);
                        assertThat(result.getBody().path("data").path("status").asText())
                            .isEqualTo(transactionExists ? "CONFIRMED" : "PAYMENT_PENDING");
                        assertThat(pg.state().counts().createReceived()).isEqualTo(transactionExists ? 1 : 0);
                        assertThat(pg.state().counts().lookupByOrderReceived()).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, product)).isEqualTo(3);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RESERVATION'", Long.class)).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RELEASE'", Long.class)).isZero();
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_history", Long.class)).isZero();
                        await().atMost(Duration.ofSeconds(5)).until(() -> pg.state().activeRequests() == 0);
                    }
                } finally {
                    await().atMost(Duration.ofSeconds(5)).until(() -> pg.state().activeRequests() == 0);
                }
                pg.close();
                assertThat(pg.isTerminated()).isTrue();
            } finally {
                admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            }
        }
    }

    private static ConfigurableApplicationContext boot(String server, PgFixtureServer pg, String ddl) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ConfigurableApplicationContext context = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test").run(
            "--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=" + ddl,
            "--datasource.mysql-jpa.main.jdbc-url=" + server + "/" + SCHEMA,
            "--commerce.pg.base-url=" + pg.baseUrl(),
            "--commerce.pg.callback-url=http://127.0.0.1:" + port + "/api/v1/payments/callback");
        try {
            assertThat(((ServletWebServerApplicationContext) context).getWebServer().getPort()).isEqualTo(port);
            assertThat(context.getBean(PgHttpProperties.class).callbackUrl().toString())
                .isEqualTo("http://127.0.0.1:" + port + "/api/v1/payments/callback");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo(ddl);
            return context;
        } catch (Throwable failure) {
            context.close();
            throw failure;
        }
    }

    private static org.springframework.http.ResponseEntity<JsonNode> call(ConfigurableApplicationContext context,
            RestTemplate http, Long user, String path, Object body, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        return http.exchange("http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort() + path,
            method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static List<Map<String, Object>> columns(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION");
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }

    private static String external(Long id) { return com.loopers.domain.payment.PaymentModel.providerOrderIdOf(id); }
}
