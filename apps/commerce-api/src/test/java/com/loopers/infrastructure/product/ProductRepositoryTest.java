package com.loopers.infrastructure.product;

import com.loopers.domain.like.Like;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductRepositoryTest {

    private final ProductRepository productRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ProductRepositoryTest(
        ProductRepository productRepository,
        LikeJpaRepository likeJpaRepository,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.productRepository = productRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품을 저장할 때, ")
    @Nested
    class Save {
        @DisplayName("저장 후 flush·clear 하고 다시 조회하면, 저장한 값이 그대로 조회된다.")
        @Transactional
        @Test
        void persistsProduct_whenReloadedAfterFlushAndClear() {
            // arrange
            Product saved = productRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            entityManager.flush();
            entityManager.clear();
            Product found = productRepository.findById(saved.getId()).orElseThrow();

            // assert
            assertThat(found.getBrandId()).isEqualTo(1L);
            assertThat(found.getName()).isEqualTo("루퍼스 티셔츠");
            assertThat(found.getPrice()).isEqualTo(new Price(1000L));
            assertThat(found.getStock().getQuantity()).isZero();
        }

        @DisplayName("가격을 수정한 뒤 flush·clear 하고 다시 조회하면, 수정된 가격이 조회된다.")
        @Transactional
        @Test
        void persistsChangedPrice_whenReloadedAfterFlushAndClear() {
            // arrange
            Product saved = productRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            saved.changePrice(new Price(2000L));
            entityManager.flush();
            entityManager.clear();
            Product found = productRepository.findById(saved.getId()).orElseThrow();

            // assert
            assertThat(found.getPrice()).isEqualTo(new Price(2000L));
        }

        @DisplayName("재고를 변경한 뒤 flush·clear 하고 다시 조회하면, 변경된 수량이 조회된다.")
        @Transactional
        @Test
        void persistsChangedStock_whenReloadedAfterFlushAndClear() {
            // arrange
            Product saved = productRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            saved.changeStock(7);
            entityManager.flush();
            entityManager.clear();
            Product found = productRepository.findById(saved.getId()).orElseThrow();

            // assert
            assertThat(found.getStock().getQuantity()).isEqualTo(7);
        }
    }

    @DisplayName("상품 단건을 조회할 때, ")
    @Nested
    class FindById {
        @DisplayName("존재하지 않는 식별자면, 빈 결과가 반환된다.")
        @Transactional
        @Test
        void returnsEmpty_whenProductIsAbsent() {
            // act
            Optional<Product> found = productRepository.findById(-1L);

            // assert
            assertThat(found).isEmpty();
        }
    }

    @DisplayName("상품 목록을 조회할 때, ")
    @Nested
    class FindAll {
        @DisplayName("삭제된 상품은 조회 결과에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            productRepository.save(new Product(1L, "살아있는 상품", new Price(1000L)));
            Product target = new Product(1L, "삭제된 상품", new Price(1000L));
            target.delete();
            productRepository.save(target);

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.LATEST, 0, 10);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("살아있는 상품");
        }

        @DisplayName("브랜드로 필터하면, 해당 브랜드의 상품만 조회된다.")
        @Test
        void filtersByBrandId() {
            // arrange
            productRepository.save(new Product(1L, "1번 브랜드 상품", new Price(1000L)));
            productRepository.save(new Product(2L, "2번 브랜드 상품", new Price(1000L)));

            // act
            List<Product> result = productRepository.findAll(2L, ProductSortType.LATEST, 0, 10);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBrandId()).isEqualTo(2L);
        }

        @DisplayName("가격 오름차순 정렬이면, 저렴한 상품이 먼저 조회된다.")
        @Test
        void sortsByPriceAsc() {
            // arrange
            productRepository.save(new Product(1L, "비싼 상품", new Price(3000L)));
            productRepository.save(new Product(1L, "저렴한 상품", new Price(1000L)));

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.PRICE_ASC, 0, 10);

            // assert
            assertThat(result).extracting(Product::getName)
                .containsExactly("저렴한 상품", "비싼 상품");
        }

        @DisplayName("최신순 정렬이면, 나중에 등록된 상품이 먼저 조회된다.")
        @Test
        void sortsByLatest() {
            // arrange
            productRepository.save(new Product(1L, "먼저 등록", new Price(1000L)));
            productRepository.save(new Product(1L, "나중에 등록", new Price(1000L)));

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.LATEST, 0, 10);

            // assert
            assertThat(result).extracting(Product::getName)
                .containsExactly("나중에 등록", "먼저 등록");
        }

        @DisplayName("좋아요 많은 순 정렬이면, 좋아요 수가 많은 상품이 먼저 조회된다.")
        @Test
        void sortsByLikesDesc() {
            // arrange
            Product few = productRepository.save(new Product(1L, "좋아요 1개", new Price(1000L)));
            Product many = productRepository.save(new Product(1L, "좋아요 2개", new Price(1000L)));
            likeJpaRepository.save(new Like(1L, few.getId()));
            likeJpaRepository.save(new Like(1L, many.getId()));
            likeJpaRepository.save(new Like(2L, many.getId()));

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.LIKES_DESC, 0, 10);

            // assert
            assertThat(result).extracting(Product::getName)
                .containsExactly("좋아요 2개", "좋아요 1개");
        }

        @DisplayName("정렬 기준이 동률이면, 식별자 역순으로 순서가 결정된다.")
        @Test
        void appliesSecondarySort_whenPrimarySortIsTied() {
            // arrange
            Product first = productRepository.save(new Product(1L, "같은 가격 A", new Price(1000L)));
            Product second = productRepository.save(new Product(1L, "같은 가격 B", new Price(1000L)));

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.PRICE_ASC, 0, 10);

            // assert
            assertThat(result).extracting(Product::getId)
                .containsExactly(second.getId(), first.getId());
        }

        @DisplayName("페이지 크기를 넘으면, 해당 페이지의 상품만 조회된다.")
        @Test
        void appliesPaging() {
            // arrange
            productRepository.save(new Product(1L, "상품 A", new Price(1000L)));
            productRepository.save(new Product(1L, "상품 B", new Price(2000L)));
            productRepository.save(new Product(1L, "상품 C", new Price(3000L)));

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.PRICE_ASC, 1, 2);

            // assert
            assertThat(result).extracting(Product::getName).containsExactly("상품 C");
        }
    }

}
