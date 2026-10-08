package com.loopers.application.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.product.CreateProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SpringBootTest
class BrandProductConcurrencyTest {
    @Autowired private CreateProductFacade createProduct;
    @Autowired private DeleteBrandFacade deleteBrand;
    @Autowired private UpdateBrandFacade updateBrand;
    @Autowired private BrandFixture brandFixture;
    @Autowired private ProductFixture productFixture;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private ProductRepository products;
    @MockitoSpyBean private BrandRepository brands;

    private ExecutorService workers;
    private CountDownLatch saveReached;
    private CountDownLatch resumeSave;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        saveReached = new CountDownLatch(1);
        resumeSave = new CountDownLatch(1);
    }

    @Test
    void 상품_등록이_먼저_진행되면_브랜드_삭제는_새_상품까지_삭제한다() throws Exception {
        // arrange
        Brand target = brandFixture.createBrand();
        long brandId = target.getId();
        Product inStock = productFixture.createProduct(brandId, "재고 있음", 3_000, 5);
        Product soldOut = productFixture.createProduct(brandId, "재고 없음", 1_000, 0);
        pauseProductRegistrationBeforeSave();

        // act
        ProductInfo created = registerProductWhileDeletingBrand(brandId);

        // assert
        assertThat(brandFixture.brand(brandId).isDeleted()).isTrue();
        assertThat(productFixture.product(inStock.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(soldOut.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(created.productId()).isDeleted()).isTrue();
        assertThat(productFixture.activeProductIds(brandId)).isEmpty();
    }

    @Test
    void 브랜드_삭제가_먼저_진행되면_상품_등록을_거절한다() throws Exception {
        // arrange
        Brand target = brandFixture.createBrand();
        long brandId = target.getId();
        Product inStock = productFixture.createProduct(brandId, "재고 있음", 3_000, 5);
        Product soldOut = productFixture.createProduct(brandId, "재고 없음", 1_000, 0);
        pauseBrandDeletionBeforeSave();

        // act
        Future<ProductInfo> registration = registerProductWhileBrandDeletionIsPaused(brandId);
        ExecutionException failure =
                assertThrows(
                        ExecutionException.class, () -> registration.get(10, TimeUnit.SECONDS));

        // assert
        CoreException error = assertInstanceOf(CoreException.class, failure.getCause());
        assertThat(error.getErrorType()).isEqualTo(ErrorType.BRAND_NOT_FOUND);
        assertThat(brandFixture.brand(brandId).isDeleted()).isTrue();
        assertThat(productFixture.productCount(brandId)).isEqualTo(2);
        assertThat(productFixture.product(inStock.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(soldOut.getId()).isDeleted()).isTrue();
        assertThat(productFixture.activeProductIds(brandId)).isEmpty();
    }

    @Test
    void 이름_수정과_삭제가_겹쳐도_삭제된_브랜드를_되살리지_않는다() throws Exception {
        // arrange
        Brand target = brandFixture.createBrand();
        long brandId = target.getId();
        Product inStock = productFixture.createProduct(brandId, "재고 있음", 3_000, 5);
        Product soldOut = productFixture.createProduct(brandId, "재고 없음", 1_000, 0);
        pauseBrandRenameBeforeSave();

        // act
        renameBrandWhileDeletingBrand(brandId);

        // assert
        assertThat(brandFixture.brand(brandId).isDeleted()).isTrue();
        assertThat(productFixture.product(inStock.getId()).isDeleted()).isTrue();
        assertThat(productFixture.product(soldOut.getId()).isDeleted()).isTrue();
        assertThat(productFixture.activeProductIds(brandId)).isEmpty();
    }

    private void pauseProductRegistrationBeforeSave() {
        doAnswer(this::pauseBeforeSave).when(products).save(any(Product.class));
    }

    private void pauseBrandDeletionBeforeSave() {
        doAnswer(this::pauseBeforeSave).when(brands).save(any(Brand.class));
    }

    private void pauseBrandRenameBeforeSave() {
        doAnswer(
                        invocation -> {
                            Brand brand = invocation.getArgument(0);
                            return brand.isDeleted()
                                    ? invocation.callRealMethod()
                                    : pauseBeforeSave(invocation);
                        })
                .when(brands)
                .save(any(Brand.class));
    }

    private ProductInfo registerProductWhileDeletingBrand(long brandId) throws Exception {
        Future<ProductInfo> registration =
                workers.submit(() -> createProduct.create(brandId, "새 상품", 2_000L));
        awaitSaveReached();
        Future<?> deletion =
                startFollowing(
                        () -> {
                            deleteBrand.delete(brandId);
                            return null;
                        });
        finishOrResume(deletion);
        ProductInfo created = registration.get(10, TimeUnit.SECONDS);
        deletion.get(10, TimeUnit.SECONDS);
        return created;
    }

    private Future<ProductInfo> registerProductWhileBrandDeletionIsPaused(long brandId)
            throws Exception {
        Future<?> deletion = workers.submit(() -> deleteBrand.delete(brandId));
        awaitSaveReached();
        Future<ProductInfo> registration =
                startFollowing(() -> createProduct.create(brandId, "새 상품", 2_000L));
        finishOrResume(registration);
        deletion.get(10, TimeUnit.SECONDS);
        return registration;
    }

    private void renameBrandWhileDeletingBrand(long brandId) throws Exception {
        Future<BrandInfo> update = workers.submit(() -> updateBrand.update(brandId, "수정된 이름"));
        awaitSaveReached();
        Future<?> deletion =
                startFollowing(
                        () -> {
                            deleteBrand.delete(brandId);
                            return null;
                        });
        finishOrResume(deletion);
        update.get(10, TimeUnit.SECONDS);
        deletion.get(10, TimeUnit.SECONDS);
    }

    private Object pauseBeforeSave(InvocationOnMock invocation) throws Throwable {
        saveReached.countDown();
        if (!resumeSave.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("선행 저장 재개 시간 초과");
        }
        return invocation.callRealMethod();
    }

    private void awaitSaveReached() throws InterruptedException, TimeoutException {
        if (!saveReached.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("선행 저장 경계 도달 시간 초과");
        }
    }

    private <T> Future<T> startFollowing(Callable<T> request)
            throws InterruptedException, TimeoutException {
        CountDownLatch started = new CountDownLatch(1);
        Future<T> future =
                workers.submit(
                        () -> {
                            started.countDown();
                            return request.call();
                        });
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("후행 요청 시작 시간 초과");
        }
        return future;
    }

    private void finishOrResume(Future<?> following) throws Exception {
        try {
            // 보호가 없으면 후행 요청이 먼저 끝난다. 보호가 있으면 대기한 뒤 선행 저장을 재개한다.
            following.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException expectedWaiting) {
            // 대기 시간 자체가 아니라 두 요청 완료 후 최종 DB 상태를 검증한다.
        } finally {
            resumeSave.countDown();
        }
    }

    @AfterEach
    void releaseWorkersAndCleanDatabase() throws InterruptedException {
        resumeSave.countDown();
        workers.shutdownNow();
        boolean terminated = workers.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(terminated).as("worker 종료 후 DB 정리").isTrue();
        cleanUp.deleteAllEntities();
    }
}
