package com.loopers.application.product;

import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.concurrent.HeldRowLock;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브랜드 삭제와 상품 등록이 동시에 실행돼도 삭제된 브랜드에 삭제되지 않은 상품이 남지 않는지 확인한다 (선택 확장).
 * 상품 등록은 브랜드를 공유 잠금(FOR SHARE)으로 읽어 삭제(배타 잠금)와 차례로 처리되고, 등록끼리는 서로 막지 않는다.
 *
 * <p>등록 요청은 짧아 실제 경쟁만으로는 겹침이 확률적이다. 그래서 실제 요청끼리 경쟁시키는 테스트와, 테스트 트랜잭션이
 * 브랜드 행 잠금을 쥔 채 서비스 요청을 줄 세우는 결정적 테스트를 함께 둔다 ({@code AdminWriteStaleOverwriteTest} 와 같은
 * 방식이며 서비스 코드의 잠금 구간에는 장벽이나 sleep 이 없다).
 */
@SpringBootTest
class ProductCreateConcurrencyTest {

    private static final int REPETITIONS = 20;
    // 서비스 요청이 잠금 없이 읽었다면 읽기를 마칠 시간을 준다. 잠금이 있는 구현의 결과에는 영향이 없다.
    private static final long SETTLE_MILLIS = 300;
    // 잠금이 걸려 있어도 막히지 않아야 하는 요청이 끝나기를 기다리는 시간. 막히면 이 시간을 넘겨 실패한다.
    private static final long BLOCKED_TIMEOUT_SECONDS = 5;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private AdminBrandFacade adminBrandFacade;

    @Autowired
    private BrandRepository brandRepository;

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

    private Runnable create(Long brandId) {
        return () -> adminProductFacade.create(brandId, "러닝화", 89_000, 10);
    }

    private long productCount() {
        return jdbcTemplate.queryForObject("select count(*) from products", Long.class);
    }

    // 삭제된 브랜드 아래에 삭제되지 않은 상품이 남아 있으면 안 된다.
    private long activeProductsUnderDeletedBrands() {
        return jdbcTemplate.queryForObject(
            "select count(*) from products p join brands b on b.id = p.brand_id "
                + "where b.deleted_at is not null and p.deleted_at is null", Long.class);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 중단되었다.", e);
        }
    }

    @DisplayName("브랜드 삭제 1개와 상품 등록 4개를 동시에 하면, 삭제된 브랜드 아래에 삭제되지 않은 상품이 남지 않는다.")
    @RepeatedTest(REPETITIONS)
    void leavesNoActiveProductUnderDeletedBrand_whenDeletingBrandWhileCreatingProducts() {
        // arrange
        Brand brand = saveBrand();
        List<Runnable> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(create(brand.getId()));
        }
        requests.add(() -> adminBrandFacade.delete(brand.getId()));

        // act
        Results results = ConcurrentRequests.run(requests);

        // assert: 등록은 삭제 앞에서 성공(그 상품도 함께 삭제됨)하거나 삭제 뒤에서 없는 브랜드로 거절된다.
        assertThat(results.successes() + results.rejections() + results.technicalErrors()).isEqualTo(5);
        assertThat(results.technicalErrors()).as("%s", results.outcomes()).isZero();
        assertThat(results.rejections() - results.rejectedWith(ErrorType.NOT_FOUND)).as("%s", results.outcomes()).isZero();
        assertThat(jdbcTemplate.queryForObject("select deleted_at is not null from brands where id = ?", Boolean.class, brand.getId()))
            .isTrue();
        assertThat(activeProductsUnderDeletedBrands()).isZero();
    }

    @DisplayName("상품 등록은 동시에 commit 된 브랜드 삭제 뒤에 도착하면, 없는 브랜드로 거절되고 상품이 만들어지지 않는다.")
    @Test
    void rejectsCreation_whenBrandIsDeletedConcurrently() {
        // arrange: 다른 요청이 브랜드를 삭제하고 아직 commit 하지 않았다.
        Brand brand = saveBrand();
        Results results;
        try (HeldRowLock lock = HeldRowLock.acquire(transactionManager, jdbcTemplate,
            "select id from brands where id = ? for update", new Object[] {brand.getId()},
            "update brands set deleted_at = current_timestamp(6) where id = ?", brand.getId())) {
            // act
            results = ConcurrentRequests.run(List.of(create(brand.getId())), () -> {
                sleep(SETTLE_MILLIS);
                lock.release();
            });
        }

        // assert: 낡은 브랜드(삭제 전)를 읽고 상품을 넣으면 삭제된 브랜드에 상품이 남는다.
        assertThat(results.rejectedWith(ErrorType.NOT_FOUND)).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(productCount()).isZero();
        assertThat(activeProductsUnderDeletedBrands()).isZero();
    }

    @DisplayName("상품 등록끼리는 서로 기다리지 않는다. 같은 브랜드의 등록 3개가 브랜드 공유 잠금이 걸려 있어도 모두 끝난다.")
    @Test
    void doesNotBlockCreationsAmongThemselves_whenBrandIsShareLocked() {
        // arrange: 다른 등록 요청이 브랜드를 공유 잠금으로 읽고 아직 commit 하지 않았다.
        Brand brand = saveBrand();
        Results results;
        try (HeldRowLock lock = HeldRowLock.acquire(transactionManager, jdbcTemplate,
            "select id from brands where id = ? for share", new Object[] {brand.getId()}, null)) {
            // act: 공유 잠금이 풀리기 전에 등록 3개가 모두 끝나야 한다. 배타 잠금이었다면 풀릴 때까지 기다려 제한 시간을 넘긴다.
            results = ConcurrentRequests.run(
                List.of(create(brand.getId()), create(brand.getId()), create(brand.getId())), () -> { }, BLOCKED_TIMEOUT_SECONDS);
        }

        // assert
        assertThat(results.successes()).as("%s", results.outcomes()).isEqualTo(3);
        assertThat(productCount()).isEqualTo(3);
    }

    @DisplayName("브랜드 삭제는 진행 중인 상품 등록이 끝나기를 기다리고, 그 상품도 함께 삭제한다.")
    @Test
    void deletesProductCreatedInFlight_whenDeletingBrand() {
        // arrange: 다른 등록 요청이 브랜드를 공유 잠금으로 읽고 상품을 넣었지만 아직 commit 하지 않았다.
        Brand brand = saveBrand();
        Results results;
        try (HeldRowLock lock = HeldRowLock.acquire(transactionManager, jdbcTemplate,
            "select id from brands where id = ? for share", new Object[] {brand.getId()},
            "insert into products (brand_id, name, price, stock, created_at, updated_at) "
                + "values (?, '러닝화', 89000, 10, current_timestamp(6), current_timestamp(6))", brand.getId())) {
            // act
            results = ConcurrentRequests.run(List.of(() -> adminBrandFacade.delete(brand.getId())), () -> {
                sleep(SETTLE_MILLIS);
                lock.release();
            });
        }

        // assert: 삭제가 등록 뒤에 처리돼 새 상품까지 지우고, 삭제된 브랜드 아래에 미삭제 상품이 남지 않는다.
        assertThat(results.successes()).as("%s", results.outcomes()).isEqualTo(1);
        assertThat(productCount()).isEqualTo(1);
        assertThat(activeProductsUnderDeletedBrands()).isZero();
        assertThat(jdbcTemplate.queryForObject("select deleted_at is not null from products", Boolean.class)).isTrue();
    }
}
