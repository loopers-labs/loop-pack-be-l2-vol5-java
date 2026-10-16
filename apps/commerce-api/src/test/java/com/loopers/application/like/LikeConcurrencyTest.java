package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.ProductService;
import com.loopers.support.ConcurrentRunner;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static com.loopers.support.ConcurrentRunner.SUCCESS;
import static com.loopers.support.ConcurrentRunner.outcomeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 좋아요 등록 · 취소의 동시 실행을 실제 DB 로 확인함 (4주차 설계 3.2, 3.3).
 * 작업의 시작만 맞추며, 어느 순서로 겹치더라도 "좋아요 수 = 관계 수" 가 지켜지는지 확인함.
 * 등록은 인터페이스와 같은 입구(LikeRetrier)로 부른다
 */
@SpringBootTest
class LikeConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 8;
    private static final Long USER_ID = 1L;

    @Autowired
    private LikeRetrier likeRetrier;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private ProductService productService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long productId;

    @BeforeEach
    void setUp() {
        productId = transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            return productService.create(brand, "상품", 1_000L).getId();
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("서로 다른 사용자들이 같은 상품에 동시에 등록하면, 모두 성공하고 좋아요 수는 사용자 수다.")
    @Test
    void countsEveryUser_whenDifferentUsersLikeConcurrently() throws Exception {
        // arrange
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            long userId = i + 1;
            tasks.add(outcomeOf(() -> likeRetrier.like(userId, productId)));
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsOnly(SUCCESS),
            () -> assertThat(relationCount()).isEqualTo(CONCURRENT_REQUESTS),
            () -> assertThat(likeCount()).isEqualTo(CONCURRENT_REQUESTS)
        );
    }

    @DisplayName("같은 사용자가 동시에 여러 번 등록하면, 늦은 요청도 다시 실행되어 성공하고 좋아요 수는 1 이다. (LIK-02)")
    @Test
    void countsOnce_whenSameUserLikesConcurrently() throws Exception {
        // arrange
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(outcomeOf(() -> likeRetrier.like(USER_ID, productId)));
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsOnly(SUCCESS),
            () -> assertThat(relationCount()).isEqualTo(1),
            () -> assertThat(likeCount()).isEqualTo(1)
        );
    }

    @DisplayName("같은 사용자가 동시에 여러 번 취소하면, 모두 성공하고 좋아요 수는 한 번만 줄어든다. (LIK-03)")
    @Test
    void decreasesOnce_whenSameUserUnlikesConcurrently() throws Exception {
        // arrange
        likeRetrier.like(USER_ID, productId);
        likeRetrier.like(USER_ID + 1, productId);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(outcomeOf(() -> likeFacade.unlike(USER_ID, productId)));
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsOnly(SUCCESS),
            () -> assertThat(relationCount()).isEqualTo(1),
            () -> assertThat(likeCount()).isEqualTo(1)
        );
    }

    @DisplayName("같은 사용자의 등록과 취소가 겹쳐도, 좋아요 수는 관계 수와 같다.")
    @Test
    void keepsCountEqualToRelations_whenLikeAndUnlikeRunConcurrently() throws Exception {
        // arrange
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            if (i % 2 == 0) {
                tasks.add(outcomeOf(() -> likeRetrier.like(USER_ID, productId)));
            } else {
                tasks.add(outcomeOf(() -> likeFacade.unlike(USER_ID, productId)));
            }
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsOnly(SUCCESS),
            () -> assertThat(relationCount()).isBetween(0L, 1L),
            () -> assertThat(likeCount()).isEqualTo(relationCount())
        );
    }

    /** 모든 작업이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private long relationCount() {
        return count("select count(*) from likes where product_id = :productId");
    }

    private long likeCount() {
        return count("select like_count from product_like_count where product_id = :productId");
    }

    private long count(String sql) {
        return transactionTemplate.execute(status ->
            ((Number) entityManager.createNativeQuery(sql).setParameter("productId", productId).getSingleResult()).longValue()
        );
    }
}
