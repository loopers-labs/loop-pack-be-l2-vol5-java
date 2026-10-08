package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockLostUpdateControlTest {
    @Autowired
    private BrandRepository brands;
    @Autowired
    private ProductRepository products;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp cleanUp;

    private JdbcTemplate jdbc;
    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() {
        // 공유 JdbcTemplate 빈의 설정을 변경하지 않는다.
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
    }

    @AfterEach
    void tearDown() {
        assertThat(workersTerminated).as("실행 중인 worker가 있으면 DB를 정리하지 않는다").isTrue();
        cleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("W3-LOST-UPDATE-01: 잠금 없는 조회값을 덮어쓰면 두 커밋 후에도 재고가 4로 남는다")
    void reproducesLostUpdateDespiteTwoSuccessfulCommits() throws Exception {
        Brand brand = brands.save(new Brand("갱신 유실 대조군"));
        Product target = products.save(new Product(brand, "대상 상품", 1000, 5));
        products.save(new Product(brands.save(new Brand("다른 브랜드")), "다른 상품", 2000, 9));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(stock(target.getId())).isEqualTo(5);
        var before = storedState();
        CountDownLatch allowWrite = new CountDownLatch(1);
        CompletableFuture<ReadEvidence> firstRead = new CompletableFuture<>();
        CompletableFuture<ReadEvidence> secondRead = new CompletableFuture<>();
        var executor = Executors.newFixedThreadPool(2);
        List<Future<Outcome>> futures = new ArrayList<>();
        List<Outcome> outcomes;
        workersTerminated = false;
        try {
            futures.add(executor.submit(() -> overwriteFromUnprotectedRead(target.getId(), firstRead, allowWrite)));
            futures.add(executor.submit(() -> overwriteFromUnprotectedRead(target.getId(), secondRead, allowWrite)));
            ReadEvidence first = firstRead.get(5, TimeUnit.SECONDS);
            ReadEvidence second = secondRead.get(5, TimeUnit.SECONDS);

            assertRead(first);
            assertRead(second);
            assertThat(first.connectionId()).isNotEqualTo(second.connectionId());
            assertThat(storedState()).as("두 조회를 확인하기 전에는 쓰기를 허용하지 않는다").isEqualTo(before);

            allowWrite.countDown();
            outcomes = List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            allowWrite.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).as("DB 재조회·정리 전에 모든 worker 종료").isTrue();
        }

        assertThat(outcomes).hasSize(2).allSatisfy(outcome -> {
            assertThat(outcome.failure()).as("SQL·대기·커밋 오류는 재현 성공이 아니다").isNull();
            assertThat(outcome.committed()).isNotNull();
            WriteEvidence write = outcome.committed();
            assertRead(write.read());
            assertThat(write.connectionBeforeWrite()).isEqualTo(write.read().connectionId());
            assertThat(write.connectionAfterWrite()).isEqualTo(write.read().connectionId());
            assertThat(write.quantity()).isEqualTo(1);
            assertThat(write.storedValue()).isEqualTo(write.read().stock() - write.quantity()).isEqualTo(4);
        });
        long successes = outcomes.stream().filter(outcome -> outcome.failure() == null).count();
        long technicalErrors = outcomes.stream().filter(outcome -> outcome.failure() != null).count();
        int deductedQuantity = outcomes.stream().map(Outcome::committed).mapToInt(WriteEvidence::quantity).sum();
        int finalStock = stock(target.getId());
        assertThat(successes).isEqualTo(2);
        assertThat(technicalErrors).isZero();
        assertThat(successes + technicalErrors).isEqualTo(2);
        assertThat(deductedQuantity).isEqualTo(2);
        assertThat(finalStock).isEqualTo(4);
        assertThat(deductedQuantity + finalStock).as("2회 차감 성공으로 집계했지만 수량 보존식은 깨진다")
            .isNotEqualTo(5);
        assertThat(finalStock).isNotEqualTo(5 - deductedQuantity);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(storedState()).as("대상 재고 외 모든 행·컬럼은 보존한다")
            .isEqualTo(withStock(before, target.getId(), 4));
    }

    private Outcome overwriteFromUnprotectedRead(long productId, CompletableFuture<ReadEvidence> readCompleted,
                                                 CountDownLatch allowWrite) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        try {
            WriteEvidence write = transaction.execute(status -> {
                ReadEvidence read = jdbc.queryForObject(
                    "SELECT stock_quantity, CONNECTION_ID() AS connection_id, "
                        + "@@transaction_isolation AS isolation_level, @@autocommit AS auto_commit "
                        + "FROM product WHERE id=?",
                    (result, row) -> new ReadEvidence(result.getInt("stock_quantity"),
                        result.getLong("connection_id"), result.getString("isolation_level"),
                        result.getBoolean("auto_commit"), status.isNewTransaction(),
                        TransactionSynchronizationManager.isActualTransactionActive()), productId);
                readCompleted.complete(read);
                awaitWritePermission(allowWrite);

                int quantity = 1;
                int nextStock = read.stock() - quantity;
                long beforeWrite = connectionId();
                // 업무 코드가 아닌 설명용 대조군이다. 반환 행 수는 성공 집계에 사용하지 않는다.
                jdbc.update("UPDATE product SET stock_quantity=? WHERE id=?", nextStock, productId);
                return new WriteEvidence(read, beforeWrite, connectionId(), quantity, nextStock);
            });
            // execute가 정상 반환해야 commit까지 끝난 성공이다.
            return new Outcome(write, null);
        } catch (Exception exception) {
            readCompleted.completeExceptionally(exception);
            return new Outcome(null, exception);
        }
    }

    private void awaitWritePermission(CountDownLatch allowWrite) {
        try {
            if (!allowWrite.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대조군 쓰기 허용 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대조군 쓰기 대기 중단", exception);
        }
    }

    private void assertRead(ReadEvidence read) {
        assertThat(read).isNotNull();
        assertThat(read.stock()).isEqualTo(5);
        assertThat(read.connectionId()).isPositive();
        assertThat(read.isolation()).isEqualTo("READ-COMMITTED");
        assertThat(read.autoCommit()).isFalse();
        assertThat(read.newTransaction()).isTrue();
        assertThat(read.transactionActive()).isTrue();
    }

    private long connectionId() {
        return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId);
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id"));
    }

    private Map<String, List<Map<String, Object>>> withStock(Map<String, List<Map<String, Object>>> before,
                                                            long productId, int quantity) {
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>(before);
        expected.put("products", before.get("products").stream().map(row -> {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (((Number) row.get("id")).longValue() == productId) {
                copy.put("stock_quantity", quantity);
            }
            return copy;
        }).toList());
        return expected;
    }

    private record ReadEvidence(int stock, long connectionId, String isolation, boolean autoCommit,
                                boolean newTransaction, boolean transactionActive) {
    }

    private record WriteEvidence(ReadEvidence read, long connectionBeforeWrite, long connectionAfterWrite,
                                 int quantity, int storedValue) {
    }

    private record Outcome(WriteEvidence committed, Exception failure) {
    }
}
