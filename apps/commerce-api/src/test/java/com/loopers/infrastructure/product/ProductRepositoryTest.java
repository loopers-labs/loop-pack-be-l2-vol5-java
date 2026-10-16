package com.loopers.infrastructure.product;

import com.loopers.domain.like.Like;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.SqlCounter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

        @DisplayName("좋아요 많은 순 정렬은 페이지 안이 아니라 전체 기준으로 적용되어, 가장 늦게 저장된 상품이라도 좋아요가 가장 많으면 첫 페이지 첫 번째로 조회된다.")
        @Test
        void sortsByLikesDescAcrossPages() {
            // arrange
            Product first = productRepository.save(new Product(1L, "좋아요 1개", new Price(1000L)));
            productRepository.save(new Product(1L, "좋아요 0개", new Price(1000L)));
            Product last = productRepository.save(new Product(1L, "좋아요 2개", new Price(1000L)));
            likeJpaRepository.save(new Like(1L, first.getId()));
            likeJpaRepository.save(new Like(1L, last.getId()));
            likeJpaRepository.save(new Like(2L, last.getId()));

            // act
            List<Product> result = productRepository.findAll(null, ProductSortType.LIKES_DESC, 0, 2);

            // assert
            assertThat(result).extracting(Product::getName).containsExactly("좋아요 2개", "좋아요 1개");
        }
    }

    @DisplayName("브랜드별 미삭제 상품을 조회할 때, ")
    @Nested
    class FindAllActiveByBrandId {
        @DisplayName("해당 브랜드의 미삭제 상품만 식별자 오름차순으로 조회되고, 삭제된 상품과 다른 브랜드 상품은 제외된다.")
        @Test
        void returnsActiveProductsOfBrandOrderedById() {
            // arrange
            Product inStock = new Product(1L, "재고 있는 상품", new Price(1000L));
            inStock.changeStock(5);
            // save 는 merge 된 복사본을 반환하므로 식별자는 반환값에서 읽는다
            Product savedInStock = productRepository.save(inStock);
            Product outOfStock = productRepository.save(new Product(1L, "품절 상품", new Price(1000L)));
            Product deleted = new Product(1L, "삭제된 상품", new Price(1000L));
            deleted.delete();
            productRepository.save(deleted);
            productRepository.save(new Product(2L, "다른 브랜드 상품", new Price(1000L)));

            // act
            List<Product> result = productRepository.findAllActiveByBrandId(1L);

            // assert
            assertThat(result).extracting(Product::getId)
                .containsExactly(savedInStock.getId(), outOfStock.getId());
        }
    }

    @DisplayName("식별자 목록으로 미삭제 상품의 재고를 조회할 때, ")
    @Nested
    class FindActiveStockQuantities {
        @DisplayName("삭제된 상품은 결과에서 빠지고, 남은 상품의 재고는 DB 에 저장된 값과 같다.")
        @Test
        void excludesDeletedAndReturnsStoredStock() {
            // arrange
            Product inStock = new Product(1L, "재고 있는 상품", new Price(1000L));
            inStock.changeStock(5);
            Product savedInStock = productRepository.save(inStock);
            Product savedOutOfStock = productRepository.save(new Product(1L, "품절 상품", new Price(1000L)));
            Product deleted = new Product(1L, "삭제된 상품", new Price(1000L));
            deleted.changeStock(9);
            deleted.delete();
            Product savedDeleted = productRepository.save(deleted);

            // act
            Map<Long, Integer> result = productRepository.findActiveStockQuantities(
                List.of(savedInStock.getId(), savedOutOfStock.getId(), savedDeleted.getId()));

            // assert
            assertThat(result).containsOnlyKeys(savedInStock.getId(), savedOutOfStock.getId());
            assertThat(result.get(savedInStock.getId())).isEqualTo(5);
            assertThat(result.get(savedOutOfStock.getId())).isZero();
        }

        @DisplayName("상품 수와 무관하게 SQL 1개로 조회한다.")
        @Test
        void executesSingleStatement() {
            // arrange
            Product first = productRepository.save(new Product(1L, "상품 1", new Price(1000L)));
            Product second = productRepository.save(new Product(1L, "상품 2", new Price(2000L)));
            Product third = productRepository.save(new Product(2L, "상품 3", new Price(3000L)));
            SqlCounter sqlCounter = new SqlCounter(entityManager.getEntityManagerFactory());
            sqlCounter.reset();

            // act
            Map<Long, Integer> result = productRepository.findActiveStockQuantities(
                List.of(first.getId(), second.getId(), third.getId()));

            // assert
            long statements = sqlCounter.count();
            sqlCounter.disable();
            assertThat(result).hasSize(3);
            assertThat(statements).isEqualTo(1);
        }

        @DisplayName("식별자 목록이 비어 있으면, SQL 을 실행하지 않고 빈 결과를 반환한다.")
        @Test
        void returnsEmptyWithoutQuery_whenIdsAreEmpty() {
            // arrange
            SqlCounter sqlCounter = new SqlCounter(entityManager.getEntityManagerFactory());
            sqlCounter.reset();

            // act
            Map<Long, Integer> result = productRepository.findActiveStockQuantities(List.of());

            // assert
            long statements = sqlCounter.count();
            sqlCounter.disable();
            assertThat(result).isEmpty();
            assertThat(statements).isZero();
        }
    }

}
