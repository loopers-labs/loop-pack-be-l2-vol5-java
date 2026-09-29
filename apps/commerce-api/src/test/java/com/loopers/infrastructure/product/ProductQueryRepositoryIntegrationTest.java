package com.loopers.infrastructure.product;

import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductWithLikes;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class ProductQueryRepositoryIntegrationTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    private ProductQueryRepository productQueryRepository;

    @Autowired
    private EntityManager entityManager;

    private ProductModel product(Long brandId, String name, long price) {
        ProductModel product = new ProductModel(brandId, name, price, 10);
        entityManager.persist(product);
        return product;
    }

    private void like(Long userId, ProductModel product) {
        entityManager.persist(new LikeModel(userId, product.getId(), ZonedDateTime.now()));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @DisplayName("상품 목록을 조회할 때, ")
    @Nested
    class FindSellable {

        @DisplayName("latest는 최신 등록순이고, 삭제된 상품은 빠지며, 개수는 삭제되지 않은 상품 수다.")
        @Test
        void latestExcludesDeleted() {
            // arrange
            ProductModel first = product(1L, "A", 1_000);
            ProductModel deleted = product(1L, "B", 1_000);
            ProductModel third = product(1L, "C", 1_000);
            deleted.delete();
            flushAndClear();

            // act
            Page<ProductWithLikes> page = productQueryRepository.findSellable(null, ProductSort.LATEST, FIRST_PAGE);

            // assert
            assertThat(page.getContent()).extracting(p -> p.product().getId()).containsExactly(third.getId(), first.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
        }

        @DisplayName("price_asc는 가격 오름차순이고, 가격이 같으면 최신 등록순이다 (P-09).")
        @Test
        void priceAscBreaksTiesByNewest() {
            // arrange
            ProductModel expensive = product(1L, "비싼", 30_000);
            ProductModel cheapOld = product(1L, "싼-먼저", 10_000);
            ProductModel cheapNew = product(1L, "싼-나중", 10_000);
            flushAndClear();

            // act
            Page<ProductWithLikes> page = productQueryRepository.findSellable(null, ProductSort.PRICE_ASC, FIRST_PAGE);

            // assert
            assertThat(page.getContent()).extracting(p -> p.product().getId())
                .containsExactly(cheapNew.getId(), cheapOld.getId(), expensive.getId());
        }

        @DisplayName("LIK-04 likes_desc는 좋아요 수 내림차순이고, 같으면 최신 등록순이며, 정렬에 쓴 좋아요 수를 함께 돌려준다.")
        @Test
        void likesDescUsesSameCountForSortAndDisplay() {
            // arrange
            ProductModel none = product(1L, "좋아요0", 1_000);
            ProductModel twoOld = product(1L, "좋아요2-먼저", 1_000);
            ProductModel twoNew = product(1L, "좋아요2-나중", 1_000);
            ProductModel three = product(1L, "좋아요3", 1_000);
            like(1L, twoOld);
            like(2L, twoOld);
            like(1L, twoNew);
            like(2L, twoNew);
            like(1L, three);
            like(2L, three);
            like(3L, three);
            flushAndClear();

            // act
            Page<ProductWithLikes> page = productQueryRepository.findSellable(null, ProductSort.LIKES_DESC, FIRST_PAGE);

            // assert
            assertThat(page.getContent())
                .extracting(p -> p.product().getId(), ProductWithLikes::likeCount)
                .containsExactly(
                    tuple(three.getId(), 3L),
                    tuple(twoNew.getId(), 2L),
                    tuple(twoOld.getId(), 2L),
                    tuple(none.getId(), 0L)
                );
            assertThat(page.getTotalElements()).isEqualTo(4);
        }

        @DisplayName("브랜드로 거르면 그 브랜드의 상품만, 페이지 크기만큼 돌려준다.")
        @Test
        void filtersByBrandAndPages() {
            // arrange
            product(1L, "브랜드1-A", 1_000);
            ProductModel brand2Old = product(2L, "브랜드2-A", 1_000);
            ProductModel brand2New = product(2L, "브랜드2-B", 1_000);
            flushAndClear();

            // act
            Page<ProductWithLikes> page = productQueryRepository.findSellable(2L, ProductSort.LATEST, PageRequest.of(0, 1));

            // assert
            assertThat(page.getContent()).extracting(p -> p.product().getId()).containsExactly(brand2New.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(brand2Old.getId()).isNotNull();
        }
    }

    @DisplayName("상품 상세를 조회할 때, ")
    @Nested
    class FindSellableById {

        @DisplayName("좋아요 수와 함께 돌려주고, 삭제된 상품은 찾지 않는다.")
        @Test
        void returnsWithLikesAndExcludesDeleted() {
            // arrange
            ProductModel alive = product(1L, "살아있음", 1_000);
            ProductModel deleted = product(1L, "삭제됨", 1_000);
            like(1L, alive);
            like(2L, alive);
            deleted.delete();
            flushAndClear();

            // act
            Optional<ProductWithLikes> found = productQueryRepository.findSellableById(alive.getId());
            Optional<ProductWithLikes> foundDeleted = productQueryRepository.findSellableById(deleted.getId());

            // assert
            assertThat(found).isPresent();
            assertThat(found.get().likeCount()).isEqualTo(2L);
            assertThat(found.get().product().getName()).isEqualTo("살아있음");
            assertThat(foundDeleted).isEmpty();
        }
    }
}
