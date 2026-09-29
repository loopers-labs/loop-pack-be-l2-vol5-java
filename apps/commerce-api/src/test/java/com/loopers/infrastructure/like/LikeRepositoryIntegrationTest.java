package com.loopers.infrastructure.like;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LikeRepositoryIntegrationTest {

    private static final Long USER = 1L;
    private static final Long OTHER = 2L;

    private final LikeService likeService;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final LikeFacade likeFacade;

    private Long productId;

    @Autowired
    LikeRepositoryIntegrationTest(
        LikeService likeService,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        LikeFacade likeFacade
    ) {
        this.likeService = likeService;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.likeFacade = likeFacade;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("LIKE-008 · 좋아요 수는 관계를 세어 얻는다. 사용자가 다르면 따로 센다.")
    @Test
    void persistsAndCounts() {
        likeFacade.like(USER, productId);
        likeFacade.like(OTHER, productId);

        assertThat(likeService.countOf(productId)).isEqualTo(2);
    }

    @DisplayName("LIKE-003 · 누른 적 없어도 취소는 성공한다. 취소는 멱등이다.")
    @Test
    void unlikeIsIdempotent() {
        assertThatCode(() -> likeFacade.unlike(USER, productId)).doesNotThrowAnyException();

        likeFacade.like(USER, productId);
        likeFacade.unlike(USER, productId);

        assertThatCode(() -> likeFacade.unlike(USER, productId)).doesNotThrowAnyException();
        assertThat(likeService.countOf(productId)).isZero();
    }

    @DisplayName("LIKE-003 · 한 사람의 취소가 다른 사람의 관계를 건드리지 않는다.")
    @Test
    void deletesOnlyOwnRelation() {
        likeFacade.like(USER, productId);
        likeFacade.like(OTHER, productId);

        likeFacade.unlike(USER, productId);

        assertThat(likeService.countOf(productId)).isEqualTo(1);

        likeFacade.unlike(OTHER, productId);

        assertThat(likeService.countOf(productId)).isZero();
    }

    @DisplayName("LIKE-001 · 같은 사용자가 20번 동시에 눌러도 관계는 하나다. 유니크 제약이 지킨다.")
    @Test
    void keepsSingleRelationUnderConcurrency() throws InterruptedException {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    likeFacade.like(USER, productId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(failed.get()).isZero();
        assertThat(likeService.countOf(productId)).isEqualTo(1);
    }

    @DisplayName("LIKE-003 · COMMON-009 · 취소는 행을 지우므로 다시 좋아요할 수 있다.")
    @Test
    void allowsRelikeAfterUnlike() {
        likeFacade.like(USER, productId);
        likeFacade.unlike(USER, productId);
        likeFacade.like(USER, productId);

        assertThat(likeService.countOf(productId)).isEqualTo(1);
    }

    @DisplayName("LIKE-004 · 006 · 삭제된 상품에는 등록할 수 없지만 기존 관계는 취소할 수 있다.")
    @Test
    void blocksLikeButAllowsUnlikeOnDeletedProduct() {
        likeFacade.like(USER, productId);
        productFacade.delete(productId);

        assertThatThrownBy(() -> likeFacade.like(USER, productId)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
        assertThat(likeService.countOf(productId)).isEqualTo(1);

        likeFacade.unlike(USER, productId);

        assertThat(likeService.countOf(productId)).isZero();
    }
}
