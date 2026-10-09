package com.loopers.product.infrastructure;

import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class StockLostUpdateControlTest {

    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[대조군] 두 트랜잭션이 재고 5를 읽고 각각 4를 저장하면 갱신 유실로 최종 재고는 4다.")
    @Test
    void reproducesLostUpdateWithStaleConstantWrite() throws Exception {
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch allowWrite = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        TraceTable trace = new TraceTable();
        trace.add("준비", "fixture commit", "-", "-", "재고 5");

        try {
            Future<Integer> first = workers.submit(() -> readThenWrite("T1", product.getId(), bothRead, allowWrite, trace));
            Future<Integer> second = workers.submit(() -> readThenWrite("T2", product.getId(), bothRead, allowWrite, trace));
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            trace.add("테스트", "두 SELECT 완료", "-", "-", "UPDATE 허용");
            allowWrite.countDown();

            int firstRead = first.get(15, TimeUnit.SECONDS);
            int secondRead = second.get(15, TimeUnit.SECONDS);
            trace.add("T1·T2", "두 commit 완료", firstRead + " / " + secondRead,
                (firstRead - 1) + " / " + (secondRead - 1), "");
            int finalStock = new TransactionTemplate(transactionManager).execute(status -> stockOf(product.getId()));
            trace.add("DB", "최종 SELECT", String.valueOf(finalStock), "-", "새 트랜잭션");
            System.out.println(trace.markdown());

            assertAll(
                () -> assertThat(firstRead).isEqualTo(5),
                () -> assertThat(secondRead).isEqualTo(5),
                () -> assertThat(finalStock).isEqualTo(4),
                () -> assertThat(2 + finalStock).isNotEqualTo(5)
            );
        } finally {
            allowWrite.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int readThenWrite(
        String actor,
        Long productId,
        CountDownLatch bothRead,
        CountDownLatch allowWrite,
        TraceTable trace
    ) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int readStock = stockOf(productId);
            trace.add(actor, "SELECT", String.valueOf(readStock), "-", "잠금 없음");
            bothRead.countDown();
            await(allowWrite);
            int staleQuantity = readStock - 1;
            entityManager.createNativeQuery("update stock set quantity = :quantity where product_id = :id")
                .setParameter("quantity", staleQuantity)
                .setParameter("id", productId)
                .executeUpdate();
            trace.add(actor, "UPDATE 완료", String.valueOf(readStock), String.valueOf(staleQuantity),
                "읽어 둔 값으로 계산");
            return readStock;
        });
    }

    private int stockOf(Long productId) {
        return ((Number) entityManager.createNativeQuery("select quantity from stock where product_id = :id")
            .setParameter("id", productId)
            .getSingleResult()).intValue();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to write stock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to write stock", exception);
        }
    }

    private static final class TraceTable {
        private final List<String> rows = new ArrayList<>();

        synchronized void add(String actor, String action, String read, String write, String note) {
            rows.add("| " + (rows.size() + 1) + " | " + actor + " | " + action + " | "
                + read + " | " + write + " | " + note + " |");
        }

        synchronized String markdown() {
            return "\n갱신 유실 대조군 실행 결과\n"
                + "| 단계 | 주체 | 작업 | 읽은 재고 | 저장 요청값 | 관찰 |\n"
                + "| --- | --- | --- | --- | --- | --- |\n"
                + String.join("\n", rows);
        }
    }
}
