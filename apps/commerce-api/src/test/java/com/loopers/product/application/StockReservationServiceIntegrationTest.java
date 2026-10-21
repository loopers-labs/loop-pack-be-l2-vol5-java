package com.loopers.product.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.StockReservationUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.loopers.support.concurrency.ConcurrentRunner.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-W5-02 · plan.md 4-1·5: 실제 MySQL에서 선점·소비·반환이 가용 재고와 선점 행을 기대대로 바꾼다.
 * 메서드는 호출자의 트랜잭션에만 참여하므로(MANDATORY) 각 호출을 TransactionTemplate으로 감싼다. 결과는 새로 읽은 DB로 확인한다.
 * 재고 예: 재고 5 · 수량 2 선점 → 3. 성공 후 3, 실패 후 5. 중복 실패로 7이 되면 버그 (plan.md 10장).
 */
@SpringBootTest
class StockReservationServiceIntegrationTest {

    private static final Long ORDER_ID = 100L;

    @Autowired
    private StockReservationUseCase stockReservationUseCase;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandModel nike;

    @BeforeEach
    void setUp() {
        nike = brandJpaRepository.save(new BrandModel("나이키", null));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("선점할 때, ")
    @Nested
    class Reserve {

        @DisplayName("ADR-W5-02 재고 5에서 2개를 선점하면 가용 재고 3, RESERVED 행 1개(수량 2)가 남는다.")
        @Test
        void reservesAndDecreasesAvailableStock() {
            // arrange
            ProductModel product = saveProduct(5);

            // act
            reserve(Map.of(product.getId(), 2));

            // assert
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(reservationRows()).containsExactly("RESERVED:2");
        }

        @DisplayName("ADR-W5-02 두 상품 중 하나라도 부족하면(5개 중 2 · 1개 중 2) 409이고, 모든 재고는 그대로이며 선점 행은 없다.")
        @Test
        void rejectsAllWhenAnyProductIsShort() {
            // arrange: id가 작은 상품이 먼저 차감된 뒤 큰 상품에서 거절된다
            ProductModel enough = saveProduct(5);
            ProductModel shortOne = saveProduct(1);

            // act & assert
            assertThatThrownBy(() -> reserve(Map.of(enough.getId(), 2, shortOne.getId(), 2)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(stockOf(enough)).isEqualTo(5);
            assertThat(stockOf(shortOne)).isEqualTo(1);
            assertThat(reservationRows()).isEmpty();
        }

        @DisplayName("ORD-03 삭제된 상품은 409이고, 재고는 그대로이며 선점 행은 없다.")
        @Test
        void rejectsDeletedProduct() {
            // arrange
            ProductModel product = saveProduct(5);
            product.delete();
            productJpaRepository.save(product);

            // act & assert
            assertThatThrownBy(() -> reserve(Map.of(product.getId(), 2)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(reservationRows()).isEmpty();
        }

        @DisplayName("ORD-03 없는 상품은 409이고, 함께 요청한 상품의 재고도 그대로다.")
        @Test
        void rejectsMissingProduct() {
            // arrange
            ProductModel product = saveProduct(5);

            // act & assert
            assertThatThrownBy(() -> reserve(Map.of(product.getId(), 2, 999_999L, 1)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(reservationRows()).isEmpty();
        }

        @DisplayName("ADR-W5-02 트랜잭션 밖에서 부르면 IllegalTransactionStateException이고, 재고는 그대로다.")
        @Test
        void requiresCallerTransaction() {
            // arrange
            ProductModel product = saveProduct(5);

            // act & assert
            assertThatThrownBy(() -> stockReservationUseCase.reserve(ORDER_ID, Map.of(product.getId(), 2)))
                .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(reservationRows()).isEmpty();
        }
    }

    @DisplayName("결제 성공으로 소비할 때, ")
    @Nested
    class Consume {

        @DisplayName("R-1 재고 5·선점 2 → 소비하면 1행이 바뀌고 재고 3·CONSUMED. 다시 소비하면 0행, 재고 3 그대로다.")
        @Test
        void consumesOnceWithoutChangingStock() {
            // arrange
            ProductModel product = saveProduct(5);
            reserve(Map.of(product.getId(), 2));

            // act
            int first = consume();
            int second = consume();

            // assert
            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(reservationRows()).containsExactly("CONSUMED:2");
        }
    }

    @DisplayName("확인된 결제 실패로 반환할 때, ")
    @Nested
    class Release {

        @DisplayName("R-2 재고 5·선점 2 → 반환하면 1행·재고 5·RELEASED. 다시 반환하면 0행, 재고는 7이 아니라 5다.")
        @Test
        void releasesOnce() {
            // arrange
            ProductModel product = saveProduct(5);
            reserve(Map.of(product.getId(), 2));

            // act
            int first = release();
            int second = release();

            // assert
            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(reservationRows()).containsExactly("RELEASED:2");
        }

        @DisplayName("R-4 소비한 뒤 반환하면 0행이고, 재고 3·CONSUMED 그대로다.")
        @Test
        void doesNotReleaseConsumed() {
            // arrange
            ProductModel product = saveProduct(5);
            reserve(Map.of(product.getId(), 2));
            consume();

            // act
            int changed = release();

            // assert
            assertThat(changed).isZero();
            assertThat(stockOf(product)).isEqualTo(3);
            assertThat(reservationRows()).containsExactly("CONSUMED:2");
        }

        @DisplayName("R-3 같은 주문 반환을 10개 트랜잭션이 동시에 → 바뀐 행 합 1 · 기술 오류 0 · 최종 재고 5")
        @Test
        void releasesOnceUnderConcurrency() throws Exception {
            // arrange
            ProductModel product = saveProduct(5);
            reserve(Map.of(product.getId(), 2));
            AtomicInteger changedTotal = new AtomicInteger();

            // act
            List<Callable<?>> releases = IntStream.range(0, 10)
                .<Callable<?>>mapToObj(i -> () -> changedTotal.addAndGet(release()))
                .toList();
            List<Outcome> outcomes = ConcurrentRunner.run(releases);

            // assert
            assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(10);
            assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();
            assertThat(changedTotal.get()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(5);
            assertThat(reservationRows()).containsExactly("RELEASED:2");
        }
    }

    private ProductModel saveProduct(int stock) {
        return productJpaRepository.save(new ProductModel(nike.getId(), "상품", 1_000, stock));
    }

    private void reserve(Map<Long, Integer> quantities) {
        transactionTemplate.executeWithoutResult(status -> stockReservationUseCase.reserve(ORDER_ID, quantities));
    }

    private int consume() {
        return transactionTemplate.execute(status -> stockReservationUseCase.consume(ORDER_ID));
    }

    private int release() {
        return transactionTemplate.execute(status -> stockReservationUseCase.release(ORDER_ID));
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private List<String> reservationRows() {
        return jdbcTemplate.queryForList(
            "SELECT CONCAT(status, ':', quantity) FROM stock_reservations ORDER BY product_id", String.class);
    }
}
