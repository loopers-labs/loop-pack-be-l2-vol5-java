package com.loopers.application.brand;

import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandDeletionException;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class BrandConcurrencyIntegrationTest {
    @Autowired private BrandRepository brands;
    @Autowired private AdminBrandService adminBrands;
    @Autowired private AdminProductService adminProducts;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private CommerceErrors errors;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void brandDeletionAndProductCreationCannotCommitAnActiveProductUnderDeletedBrand() throws Exception {
        long brandId = brands.save(new Brand("브랜드")).getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var delete = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    adminBrands.delete(UserRole.ADMIN, brandId);
                    return "deleted";
                } catch (BrandDeletionException exception) {
                    assertThat(exception.getReason()).isEqualTo(BrandDeletionException.Reason.NON_DELETED_PRODUCTS_EXIST);
                    return "blocked";
                }
            });
            var create = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    adminProducts.create(UserRole.ADMIN, brandId, "상품", 100, 0);
                    return "created";
                } catch (ProductQueryException exception) {
                    assertThat(exception.getReason()).isEqualTo(ProductQueryException.Reason.BRAND_NOT_FOUND);
                    return "unavailable";
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String result = delete.get(10, TimeUnit.SECONDS) + "/" + create.get(10, TimeUnit.SECONDS);
            assertThat(result).isIn("deleted/unavailable", "blocked/created");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN brand b ON b.id=p.brand_id "
                + "WHERE p.deleted_at IS NULL AND b.deleted_at IS NOT NULL", Long.class)).isZero();
        }
    }

    @Test
    void lockTimeoutReturnsConflictWithoutMutatingBrandOrRetrying() throws Exception {
        long brandId = brands.save(new Brand("기존 이름")).getId();
        var before = jdbc.queryForList("SELECT * FROM brand");
        assertThat(jdbc.queryForObject("SELECT @@session.innodb_lock_wait_timeout", Integer.class)).isEqualTo(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                brands.lockById(brandId).orElseThrow();
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                var response = AdminMockMvc.exchange(mvc, HttpMethod.PUT, "/api-admin/v1/brands/" + brandId,
                    "admin", "{\"name\":\"변경 시도\"}");
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(response.getStatusCode().value()).isEqualTo(409);
                assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("CONCURRENT_MODIFICATION");
                assertThat(elapsedMillis).isBetween(2500L, 8000L);
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(jdbc.queryForList("SELECT * FROM brand")).isEqualTo(before);
    }

    @Test
    void actualDatabaseDeadlockRollsBackVictimAndMapsToConflict() throws Exception {
        long first = brands.save(new Brand("원래 첫째")).getId();
        long second = brands.save(new Brand("원래 둘째")).getId();
        CountDownLatch acquired = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> deliberatelyReverseLocks(first, second, acquired));
            var right = pool.submit(() -> deliberatelyReverseLocks(second, first, acquired));
            var failures = java.util.stream.Stream.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS))
                .filter(java.util.Objects::nonNull).toList();
            assertThat(failures).singleElement().isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(errors.translate(failures.get(0)))
                .isEqualTo(new CommerceErrors.Failure(409, "CONCURRENT_MODIFICATION", "다른 요청과 충돌했습니다. 다시 요청해 주세요."));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE name='변경'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE name LIKE '원래%'", Long.class)).isEqualTo(1);
    }

    private Exception deliberatelyReverseLocks(long first, long second, CountDownLatch acquired) {
        // 실제 업무는 ID 오름차순으로 잠근다. 이 오류 fixture만 반대로 잠가 DB 교착 예외를 발생시킨다.
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Brand brand = brands.lockById(first).orElseThrow();
                brand.rename("변경");
                brands.save(brand);
                acquired.countDown();
                await(acquired);
                brands.lockById(second).orElseThrow();
            });
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행 동기화 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
