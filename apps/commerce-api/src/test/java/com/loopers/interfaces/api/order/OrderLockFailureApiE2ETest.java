package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderLockFailureApiE2ETest {

    private static final Long BUYER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final int INITIAL_STOCK = 10;
    private static final long INITIAL_BALANCE = 50_000L;
    private static final int HEAVIER_ROWS = 30;
    private static final long GIVE_UP_SECONDS = 10;
    private static final ParameterizedTypeReference<ApiResponse<Object>> ANY = new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final OrderFacade orderFacade;
    private final ProductFacade productFacade;
    private final BrandFacade brandFacade;
    private final PointFacade pointFacade;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long coat;
    private Long knit;

    @Autowired
    OrderLockFailureApiE2ETest(
        TestRestTemplate testRestTemplate,
        OrderFacade orderFacade,
        ProductFacade productFacade,
        BrandFacade brandFacade,
        PointFacade pointFacade,
        DataSource dataSource,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.orderFacade = orderFacade;
        this.productFacade = productFacade;
        this.brandFacade = brandFacade;
        this.pointFacade = pointFacade;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(10_000)).getId();
        knit = productFacade.register(brandId, "니트", Price.of(5_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(INITIAL_STOCK));
        productFacade.adjustStock(knit, Quantity.of(INITIAL_STOCK));
        pointFacade.charge(BUYER, ChargeAmount.of(INITIAL_BALANCE), NOW);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("COMMON · 다른 트랜잭션이 상품을 쥔 채 잠금 대기 한도가 지나면 503 LOCK_UNAVAILABLE 이고, 아무것도 반영되지 않는다")
    @Test
    void lockWaitTimeoutBecomesServiceUnavailable() throws Exception {
        Long orderId = placeCoatAndKnit();

        ResponseEntity<ApiResponse<Object>> response;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lock(holder, knit);
            try {
                Future<ResponseEntity<ApiResponse<Object>>> confirm = executor.submit(() -> confirm(orderId));
                response = confirm.get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
            } finally {
                holder.rollback();
            }
        } finally {
            shutdown(executor);
        }

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("LOCK_UNAVAILABLE");
        assertNothingChanged(orderId);
    }

    @DisplayName("COMMON · 확정이 교착의 희생자가 되면 503 LOCK_UNAVAILABLE 이고, 아무것도 반영되지 않는다")
    @Test
    void deadlockVictimBecomesServiceUnavailable() throws Exception {
        Long orderId = placeCoatAndKnit();
        List<Long> heavier = heavierRows();

        ResponseEntity<ApiResponse<Object>> response;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            for (Long id : heavier) {
                touch(holder, id);
            }
            lock(holder, knit);
            try {
                Future<ResponseEntity<ApiResponse<Object>>> confirm = executor.submit(() -> confirm(orderId));
                assertThat(awaitConfirmWaitingOnProduct()).as("확정이 둘째 상품에서 기다린다").isTrue();
                lock(holder, coat);
                response = confirm.get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
            } finally {
                holder.rollback();
            }
        } finally {
            shutdown(executor);
        }

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().meta().errorCode()).isEqualTo("LOCK_UNAVAILABLE");
        assertNothingChanged(orderId);
    }

    private Long placeCoatAndKnit() {
        return orderFacade.place(new OrderCreateCommand(BUYER, List.of(
            new OrderCreateCommand.Line(coat, OrderQuantity.of(2)),
            new OrderCreateCommand.Line(knit, OrderQuantity.of(1))
        )), NOW).getId();
    }

    private List<Long> heavierRows() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < HEAVIER_ROWS; i++) {
            ids.add(productFacade.register(brandId, "양말" + i, Price.of(1_000)).getId());
        }
        return ids;
    }

    private ResponseEntity<ApiResponse<Object>> confirm(Long orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(BUYER));
        return testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST, new HttpEntity<>(null, headers), ANY);
    }

    private static void lock(Connection holder, Long productId) throws Exception {
        try (PreparedStatement statement = holder.prepareStatement("SELECT id FROM product WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, productId);
            statement.executeQuery().close();
        }
    }

    private static void touch(Connection holder, Long productId) throws Exception {
        try (PreparedStatement statement = holder.prepareStatement("UPDATE product SET quantity = quantity + 1 WHERE id = ?")) {
            statement.setLong(1, productId);
            statement.executeUpdate();
        }
    }

    private boolean awaitConfirmWaitingOnProduct() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIVE_UP_SECONDS);
        try (Connection root = DriverManager.getConnection(
                System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                "root",
                System.getProperty("datasource.mysql-jpa.main.password"));
             PreparedStatement waits = root.prepareStatement(
                 "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                     + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                     + "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'product'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = waits.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) {
                        return true;
                    }
                }
                Thread.onSpinWait();
            }
            return false;
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(GIVE_UP_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private void assertNothingChanged(Long orderId) {
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM user_point WHERE user_id = ?", Long.class, BUYER))
            .isEqualTo(INITIAL_BALANCE);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, coat))
            .isEqualTo(INITIAL_STOCK);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, knit))
            .isEqualTo(INITIAL_STOCK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
            .isEqualTo("DRAFT");
    }
}
