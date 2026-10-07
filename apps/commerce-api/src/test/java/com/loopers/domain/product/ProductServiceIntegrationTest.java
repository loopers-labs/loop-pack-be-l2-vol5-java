package com.loopers.domain.product;

import com.loopers.domain.like.LikeModel;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductServiceIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품을 조회할 때,")
    @Nested
    class Get {
        @DisplayName("존재하는 상품 ID를 주면, 해당 상품 정보를 반환한다.")
        @Test
        void returnsProduct_whenValidIdIsProvided() {
            // arrange
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));

            // act
            ProductModel result = productService.getProduct(product.getId());

            // assert
            assertThat(result.getId()).isEqualTo(product.getId());
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenProductDoesNotExist() {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> productService.getProduct(999L));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 상품 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenProductIsDeleted() {
            // arrange
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> productService.getProduct(product.getId()));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class GetProducts {
        @DisplayName("삭제된 상품은 제외하고 반환한다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            ProductModel active = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));
            ProductModel deleted = productJpaRepository.save(new ProductModel("단종상품", 1000L, 1L, 0));
            deleted.delete();
            productJpaRepository.saveAndFlush(deleted);

            // act
            var result = productService.getProducts();

            // assert
            assertAll(
                () -> assertThat(result).extracting(ProductModel::getId).contains(active.getId()),
                () -> assertThat(result).extracting(ProductModel::getId).doesNotContain(deleted.getId())
            );
        }
    }

    @DisplayName("상품 목록을 정렬·페이지네이션해서 조회할 때,")
    @Nested
    class GetProductsPaged {
        @DisplayName("price_asc 정렬에서 가격이 같으면, 상품명 오름차순으로 동률이 깨진다.")
        @Test
        void breaksTieByName_whenPricesAreEqual() {
            // arrange — 이름 역순으로 저장해도 결과는 이름 오름차순이어야 한다
            productJpaRepository.save(new ProductModel("다상품", 1000L, 1L, 10));
            productJpaRepository.save(new ProductModel("가상품", 1000L, 1L, 10));
            productJpaRepository.save(new ProductModel("나상품", 1000L, 1L, 10));

            // act
            Page<ProductModel> result = productService.getProducts(null, ProductSort.PRICE_ASC, PageRequest.of(0, 10));

            // assert
            assertThat(result.getContent())
                .extracting(ProductModel::getName)
                .containsExactly("가상품", "나상품", "다상품");
        }

        @DisplayName("likes_desc 정렬은 실제 좋아요 개수 기준으로 내림차순 정렬된다.")
        @Test
        void ordersByActualLikeCount_whenSortIsLikesDesc() {
            // arrange
            ProductModel popular = productJpaRepository.save(new ProductModel("인기상품", 1000L, 1L, 10));
            ProductModel unpopular = productJpaRepository.save(new ProductModel("비인기상품", 1000L, 1L, 10));
            likeJpaRepository.save(new LikeModel(1L, popular.getId()));
            likeJpaRepository.save(new LikeModel(2L, popular.getId()));
            likeJpaRepository.save(new LikeModel(1L, unpopular.getId()));

            // act
            Page<ProductModel> result = productService.getProducts(null, ProductSort.LIKES_DESC, PageRequest.of(0, 10));

            // assert
            assertThat(result.getContent())
                .extracting(ProductModel::getId)
                .containsExactly(popular.getId(), unpopular.getId());
        }

        @DisplayName("brandId를 주면, 해당 브랜드의 상품만 반환한다.")
        @Test
        void filtersByBrandId_whenBrandIdIsProvided() {
            // arrange
            ProductModel matched = productJpaRepository.save(new ProductModel("상품A", 1000L, 1L, 10));
            productJpaRepository.save(new ProductModel("상품B", 1000L, 2L, 10));

            // act
            Page<ProductModel> result = productService.getProducts(1L, ProductSort.LATEST, PageRequest.of(0, 10));

            // assert
            assertThat(result.getContent())
                .extracting(ProductModel::getId)
                .containsExactly(matched.getId());
        }

        @DisplayName("삭제된 상품은 제외한다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            ProductModel active = productJpaRepository.save(new ProductModel("판매중", 1000L, 1L, 10));
            ProductModel deleted = productJpaRepository.save(new ProductModel("단종", 1000L, 1L, 10));
            deleted.delete();
            productJpaRepository.saveAndFlush(deleted);

            // act
            Page<ProductModel> result = productService.getProducts(null, ProductSort.LATEST, PageRequest.of(0, 10));

            // assert
            assertThat(result.getContent())
                .extracting(ProductModel::getId)
                .containsExactly(active.getId());
        }

        @DisplayName("동률인 상품이 페이지 크기보다 많아도, 페이지 사이에 중복·누락 없이 전부 조회된다.")
        @Test
        void paginatesWithoutDuplicateOrMissing_whenManyProductsAreTied() {
            // arrange — 가격·이름이 전부 같은 상품 5개, id로만 구분됨
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(productJpaRepository.save(new ProductModel("동일상품", 1000L, 1L, 10)).getId());
            }

            // act — 페이지 크기 2로 3페이지에 걸쳐 전부 순회
            List<Long> collected = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                Page<ProductModel> result =
                    productService.getProducts(null, ProductSort.PRICE_ASC, PageRequest.of(page, 2));
                result.getContent().forEach(p -> collected.add(p.getId()));
            }

            // assert
            assertThat(collected).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(collected).doesNotHaveDuplicates();
        }
    }

    @DisplayName("brandId로 고른 상품을 전부 삭제할 때,")
    @Nested
    class DeleteAllByBrandId {
        @DisplayName("해당 브랜드의 미삭제 상품은 재고 0인 것까지 모두 삭제되고, 다른 브랜드 상품은 유지된다.")
        @Test
        void deletesAllActiveProductsOfBrand_includingZeroStock_andKeepsOtherBrands() {
            // arrange
            ProductModel inStock = productJpaRepository.save(new ProductModel("재고있음", 1000L, 1L, 10));
            ProductModel soldOut = productJpaRepository.save(new ProductModel("품절", 1000L, 1L, 0));
            ProductModel otherBrand = productJpaRepository.save(new ProductModel("다른브랜드", 1000L, 2L, 10));

            // act
            productService.deleteAllByBrandId(1L);

            // assert
            assertAll(
                () -> assertThat(productJpaRepository.findById(inStock.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(productJpaRepository.findById(soldOut.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(productJpaRepository.findById(otherBrand.getId()).orElseThrow().getDeletedAt()).isNull()
            );
        }

        @DisplayName("이미 삭제된 상품의 삭제 시각은 바뀌지 않는다.")
        @Test
        void keepsOriginalDeletedAt_whenProductWasAlreadyDeleted() {
            // arrange
            ProductModel alreadyDeleted = productJpaRepository.save(new ProductModel("단종", 1000L, 1L, 10));
            alreadyDeleted.delete();
            productJpaRepository.saveAndFlush(alreadyDeleted);
            var originalDeletedAt = productJpaRepository.findById(alreadyDeleted.getId()).orElseThrow().getDeletedAt();

            // act
            productService.deleteAllByBrandId(1L);

            // assert
            assertThat(productJpaRepository.findById(alreadyDeleted.getId()).orElseThrow().getDeletedAt())
                .isEqualTo(originalDeletedAt);
        }
    }

    @DisplayName("상품을 생성할 때,")
    @Nested
    class Create {
        @DisplayName("이름·가격·브랜드ID·초기재고가 주어지면, 저장하고 반환한다.")
        @Test
        void savesAndReturnsProduct_whenValidFieldsAreProvided() {
            // act
            ProductModel result = productService.createProduct("에어맥스", 129_000L, 1L, 10);

            // assert
            assertAll(
                () -> assertThat(result.getId()).isNotNull(),
                () -> assertThat(productJpaRepository.findById(result.getId())).isPresent()
            );
        }
    }

    @DisplayName("상품을 수정할 때,")
    @Nested
    class Update {
        @DisplayName("존재하는 상품 ID를 주면, 수정하고 저장한다.")
        @Test
        void updatesAndSavesProduct_whenProductExists() {
            // arrange
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));

            // act
            productService.updateProduct(product.getId(), "에어맥스 90", 139_000L);

            // assert
            ProductModel result = productJpaRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.getName()).isEqualTo("에어맥스 90"),
                () -> assertThat(result.getPrice()).isEqualTo(139_000L),
                () -> assertThat(result.getBrandId()).isEqualTo(1L)
            );
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenProductDoesNotExist() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                productService.updateProduct(999L, "에어맥스 90", 139_000L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("상품 재고를 변경할 때,")
    @Nested
    class ChangeStock {
        @DisplayName("존재하는 상품 ID를 주면, 재고를 절대값으로 설정하고 저장한다.")
        @Test
        void setsAndSavesStock_whenProductExists() {
            // arrange
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));

            // act
            productService.changeStock(product.getId(), 500);

            // assert
            ProductModel result = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(result.getRemainingStock()).isEqualTo(500);
        }
    }

    @DisplayName("상품을 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("존재하는 상품 ID를 주면, soft delete로 표시하고 저장한다.")
        @Test
        void marksProductAsDeleted_whenProductExists() {
            // arrange
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));

            // act
            productService.deleteProduct(product.getId());

            // assert
            ProductModel result = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(result.getDeletedAt()).isNotNull();
        }

        @DisplayName("이미 삭제된 상품 ID를 다시 삭제 요청하면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenProductIsAlreadyDeleted() {
            // arrange — deleteProduct는 내부적으로 getProduct를 거치므로,
            // 조회·수정과 마찬가지로 삭제된 상품은 "없는 것"으로 취급된다 (decision 2와 같은 원칙).
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, 1L, 10));
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                productService.deleteProduct(product.getId())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
