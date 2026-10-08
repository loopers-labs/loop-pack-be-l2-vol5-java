package com.loopers.domain.product;

import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 상품 행을 바꾸는 관리자 경로가 주문 차감과 같은 비관적 락에 참여하는지 확인한다 (docs/week3/design.md 6번 섹션).
 *
 * 순서를 우연에 맡기지 않는다: 테스트가 직접 연 트랜잭션이 상품을 잠그고 변경을 flush한 채 commit을 미루고,
 * 다른 스레드의 실제 서비스 호출이 DB에서 그 행을 기다리는 것이 관찰된 뒤에야 commit한다.
 */
@SpringBootTest
class ProductWritePathLockTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 차감이 상품을 잠근 사이 들어온 관리자 상품 수정은, 차감이 commit될 때까지 기다렸다가 최신 재고 위에 가격만 바꾼다.")
    @Test
    void updateProductWaitsAndDoesNotOverwriteConcurrentStockDecrease() throws Exception {
        // arrange
        ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 1000L, 1L, 10));

        // act — 주문 확정처럼 잠그고 10 → 8 차감한 채 대기하는 동안 관리자가 가격을 수정한다
        Future<?> adminUpdate = runWhileAnotherTransactionHoldsProductLock(
            product.getId(),
            locked -> locked.decreaseStock(2),
            () -> productService.updateProduct(product.getId(), "에어맥스 2", 2000L)
        );

        // assert
        adminUpdate.get();
        ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
        assertAll(
            () -> assertThat(reloaded.getRemainingStock()).isEqualTo(8),
            () -> assertThat(reloaded.getPrice()).isEqualTo(2000L),
            () -> assertThat(reloaded.getName()).isEqualTo("에어맥스 2")
        );
    }

    @DisplayName("상품이 삭제되는 사이 들어온 관리자 재고 설정은, 삭제가 commit된 뒤의 상태로 판단해 404로 거절되고 아무것도 바꾸지 않는다.")
    @Test
    void changeStockIsRejectedWhenProductIsDeletedConcurrently() throws Exception {
        // arrange
        ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 1000L, 1L, 10));

        // act
        Future<?> adminChangeStock = runWhileAnotherTransactionHoldsProductLock(
            product.getId(),
            ProductModel::delete,
            () -> productService.changeStock(product.getId(), 50)
        );

        // assert
        ExecutionException failure = assertThrows(ExecutionException.class, adminChangeStock::get);
        assertThat(failure.getCause()).isInstanceOf(CoreException.class);
        assertThat(((CoreException) failure.getCause()).getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
        assertAll(
            () -> assertThat(reloaded.getDeletedAt()).isNotNull(),
            () -> assertThat(reloaded.getRemainingStock()).isEqualTo(10)
        );
    }

    @DisplayName("주문 차감이 브랜드 상품 하나를 잠근 사이 들어온 브랜드 일괄 삭제는, 기다렸다가 차감된 재고를 덮어쓰지 않고 삭제한다.")
    @Test
    void deleteAllByBrandIdWaitsAndDoesNotOverwriteConcurrentStockDecrease() throws Exception {
        // arrange — 차감은 더 큰 id의 상품을 잡는다: 일괄 삭제는 오름차순으로 앞 상품을 잠근 뒤 이 상품에서 기다린다
        ProductModel first = productJpaRepository.save(new ProductModel("상품1", 1000L, 1L, 10));
        ProductModel second = productJpaRepository.save(new ProductModel("상품2", 1000L, 1L, 10));

        // act
        Future<?> brandRemoval = runWhileAnotherTransactionHoldsProductLock(
            second.getId(),
            locked -> locked.decreaseStock(2),
            () -> {
                productService.deleteAllByBrandId(1L);
                return null;
            }
        );

        // assert
        brandRemoval.get();
        ProductModel reloadedFirst = productJpaRepository.findById(first.getId()).orElseThrow();
        ProductModel reloadedSecond = productJpaRepository.findById(second.getId()).orElseThrow();
        assertAll(
            () -> assertThat(reloadedFirst.getDeletedAt()).isNotNull(),
            () -> assertThat(reloadedSecond.getDeletedAt()).isNotNull(),
            () -> assertThat(reloadedSecond.getRemainingStock()).isEqualTo(8)
        );
    }

    /**
     * 별도 트랜잭션이 상품을 잠그고 holderChange를 flush한 채 commit을 미룬다. 그동안 contender를 다른 스레드에서
     * 실행하고, contender가 DB에서 상품 행을 기다리는 것이 보이면 그제서야 commit한다. contender가 끝난 Future를 돌려준다.
     */
    private Future<?> runWhileAnotherTransactionHoldsProductLock(
        Long productId,
        Consumer<ProductModel> holderChange,
        Callable<?> contender
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                ProductModel locked = productJpaRepository.findForUpdate(productId).orElseThrow();
                holderChange.accept(locked);
                entityManager.flush();
                lockHeld.countDown();
                awaitOrFail(releaseLock);
            }));
            assertThat(lockHeld.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            Future<?> contenderResult = executor.submit(contender);
            awaitUntilAnotherSessionWaitsOnProduct();

            releaseLock.countDown();
            holder.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            try {
                contenderResult.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException expectedByCaller) {
                // 실패 여부 판단은 호출한 테스트가 한다
            }
            return contenderResult;
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 상품을 건드리는 SQL이 실행 중인(=잠금 대기 중인) 다른 세션이 보일 때까지 기다린다.
     * 잠금을 쥔 세션은 flush 후 commit을 미루고 있어 실행 중인 문장이 없다(COMMAND = 'Sleep').
     * 같은 DB 계정의 세션이라 PROCESS 권한 없이도 PROCESSLIST에서 보인다.
     */
    private void awaitUntilAnotherSessionWaitsOnProduct() throws InterruptedException {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            Integer waiting = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                    + "WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND INFO LIKE '%product%'",
                Integer.class
            );
            if (waiting != null && waiting > 0) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("상품 행을 기다리는 세션이 " + TIMEOUT + " 안에 보이지 않았다");
            }
            // 관찰 가능한 DB 상태를 짧은 간격으로 다시 확인할 뿐, 실패가 우연히 나기를 기다리는 대기가 아니다.
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
