package com.loopers.application.product;

import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.order.dto.OrderConfirmCommand;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.concurrent.HeldRowLock;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쓰기 경로가 대상 행을 <b>처음 읽을 때부터 잠그는지</b>를 결정적으로 확인한다. 같은 행을 바꾸는 다른 요청이 먼저
 * commit 한 변경(재고 차감, 삭제)을 전체 컬럼 UPDATE 가 낡은 값으로 덮어쓰지 않아야 한다.
 *
 * <p>{@code AdminWriteConcurrencyTest} 는 실제 요청끼리 경쟁시키지만 관리자 요청이 짧아 겹침이 확률적이다. 여기서는 테스트
 * 트랜잭션이 대상 행의 잠금을 쥔 채 <b>동시에 commit 되는 변경</b>을 미리 수행해 두고, 서비스 요청이 모두 시작하고 잠시 뒤에
 * 잠금을 푼다. 서비스가 잠그지 않고 읽었다면 풀리기 전에 이미 낡은 값을 읽었고 풀린 뒤 그 값으로 덮어쓴다. 잠그고 읽었다면
 * 풀릴 때까지 기다렸다가 변경이 반영된 값을 읽는다. 서비스 코드의 잠금 구간에는 장벽이나 sleep 이 없다.
 */
@SpringBootTest
class AdminWriteStaleOverwriteTest {

    // 서비스 요청이 잠금 없이 읽었다면 읽기를 마칠 시간을 준다. 이 시간은 잠금이 있는 구현의 결과에는 영향이 없다.
    private static final long SETTLE_MILLIS = 300;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private AdminBrandFacade adminBrandFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Brand saveBrand() {
        return brandRepository.save(Brand.create("루퍼스"));
    }

    private Product saveProduct(Long brandId, int stock) {
        return productRepository.save(Product.create(brandId, "러닝화", new Money(3_000), new Stock(stock)));
    }

    private int stockOf(Product product) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, product.getId());
    }

    private boolean isDeleted(String table, Long id) {
        return jdbcTemplate.queryForObject("select deleted_at is not null from " + table + " where id = ?", Boolean.class, id);
    }

    // 상품 행을 잠근 테스트 트랜잭션이 change 를 수행한 채 기다리게 하고, 요청이 모두 시작한 뒤 잠금을 풀어 준다.
    private Results runBehindProductLock(Product product, String changeSql, Object[] changeArgs, Runnable request) {
        return runBehindLock("select id from products where id = ? for update", product.getId(), changeSql, changeArgs, request);
    }

    private Results runBehindLock(String lockSql, Long id, String changeSql, Object[] changeArgs, Runnable request) {
        try (HeldRowLock lock = HeldRowLock.acquire(
            transactionManager, jdbcTemplate, lockSql, new Object[] {id}, changeSql, changeArgs)) {
            return ConcurrentRequests.run(List.of(request), () -> {
                sleep(SETTLE_MILLIS);
                lock.release();
            });
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 중단되었다.", e);
        }
    }

    @DisplayName("상품 이름·가격 수정은 동시에 commit 된 재고 차감을 낡은 재고로 덮어쓰지 않는다.")
    @Test
    void keepsConcurrentStockDeduction_whenEditingProduct() {
        // arrange: 다른 요청이 재고를 10 에서 9 로 줄이고 아직 commit 하지 않았다.
        Product product = saveProduct(saveBrand().getId(), 10);

        // act
        Results results = runBehindProductLock(product,
            "update products set stock = 9 where id = ?", new Object[] {product.getId()},
            () -> adminProductFacade.update(product.getId(), "새 이름", 5_000));

        // assert
        assertThat(results.successes()).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(stockOf(product)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForMap("select name, price from products where id = ?", product.getId()))
            .containsEntry("name", "새 이름").containsEntry("price", 5_000L);
    }

    @DisplayName("상품 삭제는 동시에 commit 된 재고 차감을 낡은 재고로 덮어쓰지 않는다.")
    @Test
    void keepsConcurrentStockDeduction_whenDeletingProduct() {
        // arrange
        Product product = saveProduct(saveBrand().getId(), 10);

        // act
        Results results = runBehindProductLock(product,
            "update products set stock = 9 where id = ?", new Object[] {product.getId()},
            () -> adminProductFacade.delete(product.getId()));

        // assert
        assertThat(results.successes()).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(isDeleted("products", product.getId())).isTrue();
        assertThat(stockOf(product)).isEqualTo(9);
    }

    @DisplayName("재고 설정은 동시에 commit 된 삭제를 되살리지 않고 없는 상품으로 거절된다.")
    @Test
    void keepsConcurrentDeletion_whenChangingStock() {
        // arrange: 다른 요청이 상품을 삭제하고 아직 commit 하지 않았다.
        Product product = saveProduct(saveBrand().getId(), 10);

        // act
        Results results = runBehindProductLock(product,
            "update products set deleted_at = current_timestamp(6) where id = ?", new Object[] {product.getId()},
            () -> adminProductFacade.changeStock(product.getId(), 20));

        // assert
        assertThat(results.rejectedWith(ErrorType.NOT_FOUND)).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(isDeleted("products", product.getId())).as("재고 설정의 UPDATE 가 삭제를 되살리면 안 된다").isTrue();
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @DisplayName("상품 이름·가격 수정은 동시에 commit 된 삭제를 되살리지 않고 없는 상품으로 거절된다.")
    @Test
    void keepsConcurrentDeletion_whenEditingProduct() {
        // arrange
        Product product = saveProduct(saveBrand().getId(), 10);

        // act
        Results results = runBehindProductLock(product,
            "update products set deleted_at = current_timestamp(6) where id = ?", new Object[] {product.getId()},
            () -> adminProductFacade.update(product.getId(), "새 이름", 5_000));

        // assert
        assertThat(results.rejectedWith(ErrorType.NOT_FOUND)).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(isDeleted("products", product.getId())).as("수정의 UPDATE 가 삭제를 되살리면 안 된다").isTrue();
        assertThat(jdbcTemplate.queryForObject("select name from products where id = ?", String.class, product.getId()))
            .isEqualTo("러닝화");
    }

    @DisplayName("브랜드 수정은 동시에 commit 된 삭제를 되살리지 않고 없는 브랜드로 거절된다.")
    @Test
    void keepsConcurrentDeletion_whenRenamingBrand() {
        // arrange: 다른 요청이 브랜드를 삭제하고 아직 commit 하지 않았다.
        Brand brand = saveBrand();

        // act
        Results results = runBehindLock("select id from brands where id = ? for update", brand.getId(),
            "update brands set deleted_at = current_timestamp(6) where id = ?", new Object[] {brand.getId()},
            () -> adminBrandFacade.update(brand.getId(), "새 이름"));

        // assert
        assertThat(results.rejectedWith(ErrorType.NOT_FOUND)).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(isDeleted("brands", brand.getId())).as("수정의 UPDATE 가 삭제를 되살리면 안 된다").isTrue();
    }

    @DisplayName("브랜드 삭제는 동시에 commit 된 재고 차감을 낡은 재고로 덮어쓰지 않는다.")
    @Test
    void keepsConcurrentStockDeduction_whenDeletingBrand() {
        // arrange
        Brand brand = saveBrand();
        Product product = saveProduct(brand.getId(), 10);

        // act
        Results results = runBehindProductLock(product,
            "update products set stock = 9 where id = ?", new Object[] {product.getId()},
            () -> adminBrandFacade.delete(brand.getId()));

        // assert
        assertThat(results.successes()).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(isDeleted("brands", brand.getId())).isTrue();
        assertThat(isDeleted("products", product.getId())).isTrue();
        assertThat(stockOf(product)).isEqualTo(9);
    }

    @DisplayName("결제 시작은 동시에 commit 된 재고 설정 위에서 차감하고, 동시에 commit 된 삭제를 되살리지 않는다.")
    @Test
    void startsOnLatestStock_andKeepsConcurrentDeletion() {
        // arrange: 결제를 시작할 주문 두 개. 하나는 다른 요청이 재고를 20 으로 설정한 상품, 하나는 삭제한 상품이다.
        // PG 호출 없이 재고를 확보하는 트랜잭션 A(prepareConfirm)만 실행한다. 읽는 시점의 잠금은 이 트랜잭션이 정한다.
        Brand brand = saveBrand();
        Product restocked = saveProduct(brand.getId(), 10);
        Product removed = saveProduct(brand.getId(), 10);
        User buyer = userRepository.save(withBalance(10_000));
        Long restockedOrder = orderFacade.create(buyer.getId(), List.of(new OrderItemCommand(restocked.getId(), 2))).id();
        Long removedOrder = orderFacade.create(buyer.getId(), List.of(new OrderItemCommand(removed.getId(), 1))).id();

        // act & assert 1: 재고 설정이 commit 된 뒤의 값(20)에서 차감한다.
        Results restockResults = runBehindProductLock(restocked,
            "update products set stock = 20 where id = ?", new Object[] {restocked.getId()},
            () -> orderFacade.prepareConfirm(startCommand(buyer, restockedOrder, "attempt-restocked")));
        assertThat(restockResults.successes()).as("%s", restockResults.outcomes()).isEqualTo(1);
        assertThat(stockOf(restocked)).isEqualTo(18);

        // act & assert 2: 삭제가 commit 된 상품은 구매 불가로 거절되고 되살아나지 않는다.
        Results removeResults = runBehindProductLock(removed,
            "update products set deleted_at = current_timestamp(6) where id = ?", new Object[] {removed.getId()},
            () -> orderFacade.prepareConfirm(startCommand(buyer, removedOrder, "attempt-removed")));
        assertThat(removeResults.rejectedWith(ErrorType.PRODUCT_UNAVAILABLE)).as("%s", removeResults.outcomes()).isEqualTo(1);
        assertThat(isDeleted("products", removed.getId())).isTrue();
        assertThat(stockOf(removed)).isEqualTo(10);
    }

    private OrderConfirmCommand startCommand(User buyer, Long orderId, String attemptKey) {
        return new OrderConfirmCommand(buyer.getId(), orderId, attemptKey, "TEST", "0000-0000-0000-0000");
    }

    private User withBalance(long amount) {
        User user = User.create();
        user.chargePoint(new Money(amount));
        return user;
    }
}
