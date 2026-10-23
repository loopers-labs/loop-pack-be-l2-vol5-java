package com.loopers.application.like;

import com.loopers.application.product.AdminProductFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.Stock;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좋아요를 동시에 등록·취소해도 상품별 좋아요 수 집계가 좋아요 관계의 개수와 같은지 확인한다.
 * 집계는 좋아요 TX 안에서 원자적 UPDATE 로 ±1 하고, 같은 관계가 겹쳐 만들어져도 기본키 충돌로 한쪽이 rollback 되어
 * 집계도 함께 되돌아간다. 각 요청은 실제 Facade·Repository·MySQL 을 지나는 독립 트랜잭션이다.
 */
@SpringBootTest
class LikeCountConcurrencyTest {

    private static final int REPETITIONS = 5;

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct() {
        return exposedProducts.save(Product.create(1L, "러닝화", new Money(89_000), new Stock(10)));
    }

    private long likeRows(Long productId) {
        return jdbcTemplate.queryForObject("select count(*) from likes where product_id = ?", Long.class, productId);
    }

    private long counter(Long productId) {
        return likeService.count(productId);
    }

    private Runnable like(long userId, Long productId) {
        return () -> likeFacade.like(userId, productId);
    }

    private Runnable unlike(long userId, Long productId) {
        return () -> likeFacade.unlike(userId, productId);
    }

    @DisplayName("서로 다른 사용자가 같은 상품에 동시에 좋아요하면 사용자마다 한 번씩 세어진다.")
    @RepeatedTest(REPETITIONS)
    void countsEachUserOnce_whenManyUsersLikeSameProduct() {
        // arrange
        Product product = saveProduct();
        List<Runnable> requests = new ArrayList<>();
        for (long userId = 1; userId <= 20; userId++) {
            requests.add(like(userId, product.getId()));
        }

        // act
        Results results = ConcurrentRequests.run(requests);

        // assert
        assertThat(results.technicalErrors()).isZero();
        assertThat(results.successes()).isEqualTo(20);
        assertThat(likeRows(product.getId())).isEqualTo(20);
        assertThat(counter(product.getId())).isEqualTo(20);
    }

    @DisplayName("같은 사용자가 같은 상품에 동시에 여러 번 좋아요해도 관계도 집계도 하나다.")
    @RepeatedTest(REPETITIONS)
    void countsOnce_whenSameUserLikesConcurrently() {
        // arrange
        Product product = saveProduct();
        List<Runnable> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(like(1L, product.getId()));
        }

        // act: 겹친 요청은 기본키 충돌로 실패할 수 있다. 어느 쪽이든 집계는 관계 수와 같아야 한다.
        Results results = ConcurrentRequests.run(requests);

        // assert
        assertThat(results.successes()).isGreaterThanOrEqualTo(1);
        assertThat(likeRows(product.getId())).isEqualTo(1);
        assertThat(counter(product.getId())).isEqualTo(1);
    }

    @DisplayName("좋아요 등록과 취소가 겹쳐도 집계는 남은 관계 수와 같다.")
    @RepeatedTest(REPETITIONS)
    void matchesRows_whenLikesAndCancelsOverlap() {
        // arrange: 사용자 1~10 이 이미 좋아요한 상태
        Product product = saveProduct();
        for (long userId = 1; userId <= 10; userId++) {
            likeFacade.like(userId, product.getId());
        }
        List<Runnable> requests = new ArrayList<>();
        for (long userId = 1; userId <= 10; userId++) {
            requests.add(unlike(userId, product.getId()));
        }
        for (long userId = 11; userId <= 20; userId++) {
            requests.add(like(userId, product.getId()));
        }

        // act
        Results results = ConcurrentRequests.run(requests);

        // assert: 사용자 1~10 의 관계는 사라지고 11~20 의 관계만 남는다.
        assertThat(results.technicalErrors()).isZero();
        assertThat(likeRows(product.getId())).isEqualTo(10);
        assertThat(counter(product.getId())).isEqualTo(10);
    }

    @DisplayName("상품 삭제와 좋아요가 겹쳐도 삭제된 상품의 집계 행은 되살아나지 않고, 교착 없이 끝난다.")
    @RepeatedTest(REPETITIONS)
    void doesNotRevivePrunedRow_whenDeleteAndLikesOverlap() {
        // arrange
        Product product = saveProduct();
        Product other = saveProduct();
        List<Runnable> requests = new ArrayList<>();
        requests.add(() -> adminProductFacade.delete(product.getId()));
        for (long userId = 1; userId <= 20; userId++) {
            requests.add(like(userId, product.getId()));
        }

        // act: 삭제보다 늦게 상품을 조회한 좋아요는 NOT_FOUND 로 거절된다. 먼저 조회한 좋아요는 관계를 남길 수 있다.
        Results results = ConcurrentRequests.run(requests);

        // assert: 증가는 UPDATE 만 쓰므로 행이 지워진 뒤의 증가는 0행 갱신으로 끝난다.
        assertThat(results.technicalErrors()).isZero();
        assertThat(results.successes() + results.rejectedWith(ErrorType.NOT_FOUND)).isEqualTo(21);
        assertThat(productRepository.findActive(product.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from product_like_counts where product_id = ?", Long.class, product.getId())).isZero();
        assertThat(productRepository.findAllActive(null, ProductSort.LIKES_DESC, PageRequest.of(0, 10)).getContent())
            .extracting(Product::getId).containsExactly(other.getId());
    }

    @DisplayName("같은 좋아요를 동시에 여러 번 취소해도 집계는 한 번만 줄고 0 아래로 내려가지 않는다.")
    @RepeatedTest(REPETITIONS)
    void decreasesOnce_whenSameLikeIsCancelledConcurrently() {
        // arrange: 사용자 1 만 좋아요, 사용자 2 도 좋아요(남아야 한다)
        Product product = saveProduct();
        likeFacade.like(1L, product.getId());
        likeFacade.like(2L, product.getId());
        List<Runnable> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(unlike(1L, product.getId()));
        }

        // act
        Results results = ConcurrentRequests.run(requests);

        // assert
        assertThat(results.technicalErrors()).isZero();
        assertThat(likeRows(product.getId())).isEqualTo(1);
        assertThat(counter(product.getId())).isEqualTo(1);
    }
}
