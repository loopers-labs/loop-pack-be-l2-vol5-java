package com.loopers.concurrency;

import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 갱신 유실(lost update)이 왜 문제인지 보여 주는 <b>음성 대조군</b>이다. 제품 구현이 아니다.
 *
 * <p>잠금도 version도 없이 "읽고 → 읽은 값에서 1을 뺀 값을 저장"하는 두 트랜잭션을 동시에 돌리면,
 * 둘 다 성공했는데도 재고가 한 번만 줄어든다. 이 결과가 틀렸다는 것을 assertion으로 확인해 두면,
 * 이후 실제 서비스 경쟁 테스트(재고·포인트·충전과 결제)가 무엇을 막아야 하는지 기준이 된다.
 *
 * <p>이 테스트의 설계 원칙:
 * <ul>
 *   <li>JPA 대신 JDBC를 직접 쓴다. 독립된 트랜잭션 두 개와 커넥션 두 개를 코드에서 눈으로 확인할 수 있고,
 *       변경 감지나 영속성 컨텍스트가 결과에 끼어들 여지가 없다.</li>
 *   <li>"두 트랜잭션이 모두 읽은 뒤에야 쓰기를 허용"하는 장벽은 이 대조군 안에만 둔다.
 *       실제 서비스 테스트에는 이런 장벽이나 sleep을 넣지 않는다 ({@link ConcurrentRunner} 참고).</li>
 *   <li>시간 초과나 SQL 오류는 갱신 유실을 재현한 것으로 세지 않는다. 두 트랜잭션이 모두 오류 없이
 *       commit한 경우에만 재현으로 인정한다.</li>
 *   <li>데이터는 worker가 시작하기 전에 commit하고, 테스트 전체를 하나의 부모 트랜잭션으로 감싸지 않는다.
 *       결과는 두 트랜잭션이 끝난 뒤 새로 조회해서 판단한다.</li>
 *   <li>메모리 DB나 mock이 아니라 기존 MySQL 테스트 컨테이너를 쓴다.</li>
 * </ul>
 */
@SpringBootTest
class LostUpdateControlTest {

    private static final int INITIAL_STOCK = 5;
    private static final int WORKERS = 2;
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(15);
    private static final long BARRIER_TIMEOUT_SECONDS = 5;

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재고 5를 잠금 없이 읽은 두 트랜잭션이 각자 4를 저장하면, 성공 2건인데 최종 재고가 4가 되어 수량 식이 깨진다.")
    @Test
    void reproducesLostUpdate_whenTwoTransactionsReadFiveAndEachStoreFour() {
        // arrange: 최소 두 worker가 각자 커넥션을 잡을 수 있어야 하고, 재고 5인 상품은 worker 시작 전에 commit해 둔다.
        assertThat(dataSource.getMaximumPoolSize())
            .as("worker 수만큼 커넥션을 확보할 수 있어야 한다")
            .isGreaterThanOrEqualTo(WORKERS);
        Long productId = saveProductWithStock(INITIAL_STOCK);
        PostReadBarrier barrier = new PostReadBarrier();
        List<Callable<Integer>> tasks = List.of(
            () -> readThenStoreDecreasedValue(productId, barrier),
            () -> readThenStoreDecreasedValue(productId, barrier)
        );

        // act
        List<WorkerResult<Integer>> results;
        try {
            results = ConcurrentRunner.runTogether(tasks, RUN_TIMEOUT);
        } finally {
            // 한쪽이 실패해 장벽에서 기다리는 worker가 남지 않도록 대기를 해제한다.
            barrier.release();
        }

        // assert 1: 시간 초과·SQL 오류는 갱신 유실 재현으로 세지 않는다. 두 트랜잭션이 모두 오류 없이 commit했어야 한다.
        assertThat(results)
            .as("두 트랜잭션이 모두 오류 없이 commit해야 갱신 유실 재현으로 인정한다 (결과: %s)", results)
            .extracting(WorkerResult::outcome)
            .containsOnly(Outcome.SUCCESS);

        // assert 2: 두 트랜잭션이 읽은 값은 모두 5였고, 그 결과 성공 2건 / 최종 재고 4가 되어 수량 식이 깨진다.
        int successCount = (int) ConcurrentRunner.count(results, Outcome.SUCCESS);
        int soldQuantity = successCount; // 성공한 트랜잭션마다 1개씩 판 것으로 센다
        int finalStock = productJpaRepository.findById(productId).orElseThrow().getStock(); // 새 경계에서 재조회
        assertAll(
            () -> assertThat(results).extracting(WorkerResult::value)
                .as("두 트랜잭션이 읽은 재고").containsExactly(INITIAL_STOCK, INITIAL_STOCK),
            () -> assertThat(successCount).as("성공한 트랜잭션 수").isEqualTo(2),
            () -> assertThat(finalStock).as("최종 재고").isEqualTo(4),
            // 올바른 결과라면 판매 수량 + 남은 재고 = 처음 재고여야 한다. 2 + 4 = 6 이라 5와 맞지 않는다.
            () -> assertThat(soldQuantity + finalStock)
                .as("판매 수량 + 최종 재고는 처음 재고(%d)와 같지 않아야 갱신 유실이 재현된 것이다", INITIAL_STOCK)
                .isNotEqualTo(INITIAL_STOCK)
        );
    }

    /**
     * 하나의 독립된 트랜잭션에서 "잠금 없이 읽고, 읽은 값 - 1을 저장"한다. 의도적으로 틀린 방식이다.
     *
     * @return 이 트랜잭션이 읽은 재고
     */
    private int readThenStoreDecreasedValue(Long productId, PostReadBarrier barrier) throws Exception {
        // 커넥션을 직접 얻고 autocommit을 꺼서, 이 worker만의 독립된 트랜잭션을 연다.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 잠금 없는 SELECT다. FOR UPDATE도 version 조건도 없다.
                int read = selectStock(connection, productId);

                // 두 트랜잭션이 모두 읽은 값이 5임을 확인한 다음에야 쓰기를 허용한다.
                barrier.awaitBothReads(read);

                // 읽어 둔 값에서 1을 뺀 "상수"를 저장한다. stock = stock - 1 처럼 DB가 현재 값 기준으로 계산하는 SQL이 아니다.
                // 조건(WHERE stock = 읽은 값)도 version도 없으므로, 다른 트랜잭션이 먼저 바꿨어도 그대로 덮어쓴다.
                updateStock(connection, productId, read - 1);
                connection.commit();
                return read;
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw e;
            }
        }
    }

    private static int selectStock(Connection connection, Long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT stock FROM product WHERE id = ?")) {
            statement.setLong(1, productId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("재고를 읽을 상품이 없다. id = " + productId);
                }
                return resultSet.getInt(1);
            }
        }
    }

    private static void updateStock(Connection connection, Long productId, int newStock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE product SET stock = ? WHERE id = ?")) {
            statement.setInt(1, newStock);
            statement.setLong(2, productId);
            // 반환된 행 수는 쓰지 않는다. 두 번째 UPDATE는 4를 4로 바꾸므로 "값이 실제로 바뀐 행"이 0일 수 있지만,
            // 이것을 업무 실패로 집계하지 않는다.
            statement.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private Long saveProductWithStock(int stock) {
        BrandModel brand = brandJpaRepository.save(new BrandModel("대조군 브랜드"));
        return productJpaRepository.save(new ProductModel(brand.getId(), "대조군 상품", 10_000L, stock)).getId();
    }

    /**
     * 두 트랜잭션이 모두 읽기를 마친 뒤에만 쓰기를 허용하는 장벽이다. <b>대조군에서만 쓴다.</b>
     * 마지막으로 도착한 쪽이 "두 값이 모두 처음 재고였는지"를 확인하고, 아니면 장벽을 깨서 둘 다 쓰지 못하게 한다.
     */
    private static final class PostReadBarrier {

        private final AtomicInteger readsOfInitialStock = new AtomicInteger();
        private final CyclicBarrier barrier = new CyclicBarrier(WORKERS, this::verifyBothReadInitialStock);

        void awaitBothReads(int readValue) throws Exception {
            if (readValue == INITIAL_STOCK) {
                readsOfInitialStock.incrementAndGet();
            }
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /** 장벽에서 기다리는 worker가 남지 않도록 대기를 끊는다. */
        void release() {
            barrier.reset();
        }

        private void verifyBothReadInitialStock() {
            if (readsOfInitialStock.get() != WORKERS) {
                throw new IllegalStateException(
                    "두 트랜잭션이 모두 재고 " + INITIAL_STOCK + "를 읽지 못해 쓰기를 허용하지 않는다.");
            }
        }
    }
}
