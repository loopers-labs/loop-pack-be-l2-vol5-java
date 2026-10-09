package com.loopers.application.order;

import com.loopers.application.point.PointFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "datasource.mysql-jpa.main.connection-init-sql=SET SESSION innodb_lock_wait_timeout=1")
@AutoConfigureMockMvc
class LockFailureIntegrationTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private PointFacade points;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private MockMvc mvc;

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    @Test
    void lockTimeoutRejectsHttpConfirmationWithoutChangingOrderStockOrPoints() throws Exception {
        long userId = registration.register().getId();
        long productId = product("ordered");
        points.charge(userId, 10_000L);
        long orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.prepareStatement("select id from point_balance where user_id = ? for update")) {
                    statement.setLong(1, userId);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                    }
                }
                var response = mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", userId))
                    .andExpect(status().isServiceUnavailable()).andReturn();
                assertThat(sqlCode(response.getResolvedException())).isEqualTo(1205);
            } finally {
                holder.rollback();
            }
        }
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(balances.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(10_000L);
        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getPaidAmount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from point_usage", Long.class)).isZero();
    }

    @Test
    void timeoutAfterAnUpdateRollsBackTheWholeApplicationTransaction() throws Exception {
        long first = product("first");
        long second = product("second");
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.prepareStatement("update product set quantity = 3 where id = ?")) {
                    statement.setLong(1, second);
                    statement.executeUpdate();
                }
                assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    jdbc.update("update product set quantity = quantity - 1 where id = ?", first);
                    jdbc.update("update product set quantity = quantity - 1 where id = ?", second);
                })).isInstanceOf(PessimisticLockingFailureException.class)
                    .satisfies(error -> assertThat(sqlCode(error)).isEqualTo(1205));
                holder.commit(); // 다른 요청의 성공은 실패한 트랜잭션의 롤백과 별개다.
            } finally {
                holder.rollback();
            }
        }
        assertThat(products.findById(first).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(products.findById(second).orElseThrow().getStockQuantity()).isEqualTo(3);
    }

    @Test
    void reversedLockOrderControlRollsBackVictimAndPreservesWinner() throws Exception {
        long first = product("first");
        long second = product("second");
        CountDownLatch bothUpdated = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> reversedUpdate(first, second, bothUpdated));
            var b = executor.submit(() -> reversedUpdate(second, first, bothUpdated));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(0, 1213);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(products.findById(first).orElseThrow().getStockQuantity()).isEqualTo(4);
        assertThat(products.findById(second).orElseThrow().getStockQuantity()).isEqualTo(4);
    }

    private int reversedUpdate(long first, long second, CountDownLatch bothUpdated) {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.update("update product set quantity = quantity - 1 where id = ?", first);
                // 역순 교착 재현용 SQL 대조군에만 장벽을 둔다. 실제 서비스에는 넣지 않는다.
                bothUpdated.countDown();
                try {
                    assertThat(bothUpdated.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                jdbc.update("update product set quantity = quantity - 1 where id = ?", second);
            });
            return 0;
        } catch (PessimisticLockingFailureException exception) {
            return sqlCode(exception);
        }
    }

    private static int sqlCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getErrorCode();
            }
        }
        throw new AssertionError("SQL exception missing", error);
    }

    private long product(String name) {
        long brandId = brands.save(new BrandModel(name, null)).getId();
        return products.save(new ProductModel(brandId, name, 1_000L, 5)).getId();
    }
}
