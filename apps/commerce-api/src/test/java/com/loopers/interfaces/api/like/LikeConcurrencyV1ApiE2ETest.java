package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.ProductListCache;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeConcurrencyV1ApiE2ETest {
    @Autowired private TestRestTemplate rest;
    @Autowired private LikeFacade facade;
    @Autowired private UserRepository users;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private ProductListCache cache;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private RedisCleanUp redisCleanup;
    @MockitoSpyBean private LikeRepository likes;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
        redisCleanup.truncateAll();
    }

    @Test
    void concurrentAddsReturnOkWithOneRelationAndOneInvalidation() throws Exception {
        Fixture fixture = fixture();
        long brandVersion = cache.currentVersion(fixture.brandId());
        long allVersion = cache.currentVersion(null);
        CyclicBarrier bothReadAbsent = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            bothReadAbsent.await(5, TimeUnit.SECONDS);
            return found;
        }).when(likes).findByUserIdAndProductId(fixture.userId(), fixture.productId());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>>> responses;
        try {
            var first = workers.submit(() -> add(fixture));
            var second = workers.submit(() -> add(fixture));
            responses = List.of(await(first), await(second));
        } finally {
            stop(workers);
        }

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
            assertThat(response.getBody().data().liked()).isTrue();
            assertThat(response.getBody().data().userId()).isEqualTo(fixture.userId());
            assertThat(response.getBody().data().productId()).isEqualTo(fixture.productId());
        });
        assertThat(likes.countByProductId(fixture.productId())).isEqualTo(1);
        assertThat(cache.currentVersion(fixture.brandId())).isEqualTo(brandVersion + 1);
        assertThat(cache.currentVersion(null)).isEqualTo(allVersion + 1);
    }

    @Test
    void duplicateAfterAnEarlierSnapshotDoesNotPreventTheTransactionFromCommitting() throws Exception {
        Fixture fixture = fixture();
        long version = cache.currentVersion(fixture.brandId());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(likes.findByUserIdAndProductId(fixture.userId(), fixture.productId())).isEmpty();
                await(worker.submit(() -> facade.add(fixture.userId(), fixture.productId())));

                assertThat(facade.add(fixture.userId(), fixture.productId()).liked()).isTrue();
                brands.save(Brand.create("After duplicate"));
            });
        } finally {
            stop(worker);
        }

        assertThat(likes.countByProductId(fixture.productId())).isEqualTo(1);
        assertThat(brands.existsByName("After duplicate")).isTrue();
        assertThat(cache.currentVersion(fixture.brandId())).isEqualTo(version + 1);
    }

    @Test
    void rollbackRemovesTheInsertedRelationAndDoesNotInvalidateCache() {
        Fixture fixture = fixture();
        long version = cache.currentVersion(fixture.brandId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(facade.add(fixture.userId(), fixture.productId()).liked()).isTrue();
            assertThat(likes.countByProductId(fixture.productId())).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(likes.countByProductId(fixture.productId())).isZero();
        assertThat(cache.currentVersion(fixture.brandId())).isEqualTo(version);
    }

    private Fixture fixture() {
        User user = users.save(User.create());
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = products.save(Product.create(brand.getId(), "Air", 100L));
        return new Fixture(user.getId(), brand.getId(), product.getId());
    }

    private ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> add(Fixture fixture) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", fixture.userId().toString());
        return rest.exchange("/api/v1/products/" + fixture.productId() + "/likes", HttpMethod.POST,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("동시 요청이 제한 시간 안에 완료되지 않았습니다.", exception);
        }
    }

    private void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private record Fixture(Long userId, Long brandId, Long productId) {}
}
