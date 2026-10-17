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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.Stream;

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
    private final JdbcTemplate jdbcTemplate;

    private Long productId;

    @Autowired
    LikeRepositoryIntegrationTest(
        LikeService likeService,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        LikeFacade likeFacade,
        JdbcTemplate jdbcTemplate
    ) {
        this.likeService = likeService;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.likeFacade = likeFacade;
        this.jdbcTemplate = jdbcTemplate;
    }

    private long counter() {
        return jdbcTemplate.query("SELECT like_count FROM product_like_count WHERE product_id = ?",
            rs -> rs.next() ? rs.getLong(1) : 0L, productId);
    }

    private long relations() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_like WHERE product_id = ?", Long.class, productId);
    }

    private int runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        AtomicInteger failed = new AtomicInteger();
        for (Runnable task : tasks) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
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
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        return failed.get();
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

    @DisplayName("LIKE-008 · 좋아요 수는 카운터에서 읽고, 관계 행 수와 같다. 사용자가 다르면 따로 센다.")
    @Test
    void persistsAndCounts() {
        likeFacade.like(USER, productId);
        long returned = likeFacade.like(OTHER, productId);

        assertThat(returned).isEqualTo(2);
        assertThat(counter()).isEqualTo(2).isEqualTo(relations());
    }

    @DisplayName("Q-6 · 누른 적 없는 취소 · 같은 취소의 반복 · 같은 좋아요의 반복은 카운터를 바꾸지 않는다 — 관계 행이 실제로 바뀔 때만 증감한다")
    @Test
    void counterMovesOnlyWhenRelationChanges() {
        likeFacade.unlike(USER, productId);
        long afterUnlikeOfNothing = counter();

        likeFacade.like(USER, productId);
        likeFacade.like(USER, productId);
        long afterRepeatedLike = counter();

        likeFacade.unlike(USER, productId);
        likeFacade.unlike(USER, productId);

        assertThat(afterUnlikeOfNothing).isZero();
        assertThat(afterRepeatedLike).isEqualTo(1);
        assertThat(counter()).isZero().isEqualTo(relations());
    }

    @DisplayName("Q-6 · 여러 사용자가 같은 상품에 동시에 좋아요 · 취소해도 카운터는 관계 행 수와 같다")
    @Test
    void counterMatchesRelationsUnderConcurrentLikesAndUnlikes() throws InterruptedException {
        List<Runnable> stayers = LongStream.rangeClosed(1, 20)
            .<Runnable>mapToObj(user -> () -> likeFacade.like(user, productId)).toList();
        List<Runnable> leavers = LongStream.rangeClosed(21, 30)
            .<Runnable>mapToObj(user -> () -> {
                likeFacade.like(user, productId);
                likeFacade.unlike(user, productId);
            }).toList();

        int failed = runConcurrently(Stream.concat(stayers.stream(), leavers.stream()).toList());

        assertThat(failed).isZero();
        assertThat(relations()).isEqualTo(20);
        assertThat(counter()).isEqualTo(20);
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

    @DisplayName("LIKE-001 · 같은 사용자가 20번 동시에 눌러도 관계는 하나이고 카운터도 1 이다. 유니크 제약이 지킨다.")
    @Test
    void keepsSingleRelationUnderConcurrency() throws InterruptedException {
        List<Runnable> sameUser = Collections.nCopies(20, () -> likeFacade.like(USER, productId));

        int failed = runConcurrently(sameUser);

        assertThat(failed).isZero();
        assertThat(relations()).isEqualTo(1);
        assertThat(counter()).isEqualTo(1);
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
