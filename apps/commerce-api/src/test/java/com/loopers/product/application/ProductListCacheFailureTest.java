package com.loopers.product.application;

import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.domain.ProductSort;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "product.list-cache.ttl=1s")
@Import(ControllableProductListCache.Config.class)
public class ProductListCacheFailureTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    private ControllableProductListCache cache;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @Autowired
    private ProductListQueryService productListQueryService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;


    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() throws InterruptedException {
        cache.reset();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }


    @DisplayName("가격 변경이 rollback되면 변경 전 목록을 그대로 돌려준다 — 같은 트랜잭션 안의 조회가 commit 안 된 가격을 게시하지 않는다.")
    @Test
    void rolledBackChangeIsNotPublished() {
        // arrange
        Long brandId = brand();
        Long productId = product(brandId, "A", 1_000);
        list(brandId);

        // act — 가격을 바꾸고 같은 트랜잭션에서 목록을 읽은 뒤 실패한다
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            productCommandUseCase.update(productId, "A", 9_000);
            list(brandId);
            throw new IllegalStateException("가격 변경 뒤 실패");
        })).isInstanceOf(IllegalStateException.class);

        // assert
        assertThat(pricesFromDb(brandId)).containsExactly(1_000L);
        assertThat(pricesFromCacheOrDb(brandId)).containsExactly(1_000L);
    }

    @DisplayName("무효화와 commit 사이에 들어온 조회가 옛 값을 채워도, 변경이 끝난 뒤 시작한 조회는 새 가격을 받는다.")
    @Test
    void readBetweenInvalidationAndCommitDoesNotLeaveStaleValue() throws Exception {
        // arrange
        Long brandId = brand();
        Long productId = product(brandId, "A", 1_000);
        list(brandId);

        // act — 관리자 변경이 무효화를 마친 지점에서 멈춘 사이에 고객이 조회한다
        cache.pauseAfterNextInvalidate();
        Future<?> admin = executor.submit(() -> productCommandUseCase.update(productId, "A", 3_000));
        assertThat(cache.awaitInvalidatePaused(WAIT)).isTrue();
        list(brandId);
        cache.releaseInvalidate();
        admin.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        // assert — 변경 완료 뒤 시작한 순차 조회
        assertThat(pricesFromCacheOrDb(brandId)).containsExactly(3_000L);
    }

    @DisplayName("늦은 캐시 채움: 변경 전에 DB를 읽은 조회가 변경 commit 뒤에 저장해도, 이후 조회는 새 가격을 받는다.")
    @Test
    void lateFillOfOldResultIsNotServed() throws Exception {
        // arrange
        Long brandId = brand();
        Long productId = product(brandId, "A", 1_000);

        // act — ① 이전 DB 결과 읽기 → ② 관리자 변경 commit → ③ 이전 결과 캐시 저장 → ④ 새 조회
        cache.pauseBeforeNextSave();
        Future<Page<ProductInfo>> oldReader = executor.submit(() -> list(brandId));
        assertThat(cache.awaitSavePaused(WAIT )).isTrue();
        productCommandUseCase.update(productId, "A", 3_000);
        cache.releaseSave();
        Page<ProductInfo> oldResult = oldReader.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        // assert — 이미 시작한 조회는 자기가 읽은 시점의 결과, 변경 뒤 시작한 조회는 새 결과
        assertThat(oldResult.getContent()).extracting(ProductInfo::price).containsExactly(1_000L);
        assertThat(pricesFromCacheOrDb(brandId)).containsExactly(3_000L);
    }

    @DisplayName("commit 뒤 무효화가 실패해도 관리자 변경은 성공으로 남고, 오래된 가격은 TTL 안에서만 보이다가 원본과 같아진다.")
    @Test
    void failedInvalidationKeepsCommitAndRecoversByTtl() {
        // arrange
        Long brandId = brand();
        Long productId = product(brandId, "A", 1_000);
        list(brandId);

        // act
        cache.failInvalidate(true);
        productCommandUseCase.update(productId, "A", 3_000);
        cache.failInvalidate(false);

        // assert — DB의 commit은 되돌아가지 않는다
        assertThat(pricesFromDb(brandId)).containsExactly(3_000L);
        // 허용한 표시 신선도: 가격은 TTL(테스트 1초) 동안 옛 값일 수 있고, 만료 뒤 원본과 같아진다
        await().atMost(WAIT).until(() -> pricesFromCacheOrDb(brandId).equals(List.of(3_000L)));
    }

    @DisplayName("무효화가 실패해도 삭제된 상품은 캐시를 통해 노출되지 않는다 — 비노출은 표시 신선도로 허용하지 않는다.")
    @Test
    void deletedProductIsNeverServedFromStaleCache() {
        // arrange
        Long brandId = brand();
        Long kept = product(brandId, "A", 1_000);
        Long deleted = product(brandId, "B", 2_000);
        list(brandId);

        // act
        cache.failInvalidate(true);
        productCommandUseCase.delete(deleted);
        cache.failInvalidate(false);

        // assert — TTL을 기다리지 않고 바로
        assertThat(list(brandId).getContent()).extracting(ProductInfo::id).containsExactly(kept);
    }

    private Long brand() {
        return brandCommandUseCase.create("브랜드", null).id();
    }

    private Long product(Long brandId, String name, long price) {
        return productCommandUseCase.create(brandId, name, price, 10).id();
    }

    private Page<ProductInfo> list(Long brandId) {
        return productListQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
    }

    private List<Long> pricesFromCacheOrDb(Long brandId) {
        return list(brandId).getContent().stream().map(ProductInfo::price).toList();
    }

    private List<Long> pricesFromDb(Long brandId) {
        return productQueryService.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE).getContent().stream()
                .map(ProductInfo::price).toList();
    }
}
