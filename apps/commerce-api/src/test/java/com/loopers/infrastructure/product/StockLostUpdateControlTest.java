package com.loopers.infrastructure.product;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockLostUpdateControlTest {

    private static final int INITIAL_STOCK = 5;
    private static final long TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private long productId;

    @Autowired
    StockLostUpdateControlTest(DataSource dataSource, JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO brand (id, name, created_at, updated_at) VALUES (1, '무신사', NOW(6), NOW(6))");
        jdbcTemplate.update("INSERT INTO product (id, brand_id, name, price, quantity, created_at, updated_at) "
            + "VALUES (1, 1, '코트', 10000, ?, NOW(6), NOW(6))", INITIAL_STOCK);
        productId = 1L;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private record Attempt(int read, boolean committed) {
    }

    @DisplayName("대조군 · 잠금 없이 둘이 5 를 읽고 각자 상수 4 를 쓰면, 두 차감이 모두 성공해도 재고는 1 만 준다 — 갱신 유실")
    @Test
    void reproducesLostUpdate() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> readThenWriteConstant(bothRead)));
            }
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS));
            }

            assertThat(attempts).as("두 트랜잭션 모두 커밋된 5 를 잠금 없이 읽었다")
                .extracting(Attempt::read).containsExactly(INITIAL_STOCK, INITIAL_STOCK);
            assertThat(attempts).as("두 차감 모두 커밋되었다 — 업무상 성공 2")
                .allMatch(Attempt::committed);
            int finalStock = currentStock();
            assertThat(finalStock).isEqualTo(INITIAL_STOCK - 1);
            assertThat(attempts.size() + finalStock)
                .as("성공한 차감 수 + 최종 재고 ≠ 초기 재고 — 한 차감이 사라졌다")
                .isNotEqualTo(INITIAL_STOCK);
        } finally {
            bothRead.reset();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Attempt readThenWriteConstant(CyclicBarrier bothRead) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int read;
                try (PreparedStatement select = connection.prepareStatement("SELECT quantity FROM product WHERE id = ?")) {
                    select.setLong(1, productId);
                    try (ResultSet rows = select.executeQuery()) {
                        rows.next();
                        read = rows.getInt(1);
                    }
                }
                bothRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try (PreparedStatement update = connection.prepareStatement("UPDATE product SET quantity = ? WHERE id = ?")) {
                    update.setInt(1, read - 1);
                    update.setLong(2, productId);
                    update.executeUpdate();
                }
                connection.commit();
                return new Attempt(read, true);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private int currentStock() {
        Integer quantity = jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, productId);
        return quantity == null ? -1 : quantity;
    }
}
