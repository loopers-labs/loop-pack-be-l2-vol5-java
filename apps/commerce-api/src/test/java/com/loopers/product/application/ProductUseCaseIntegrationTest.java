package com.loopers.product.application;

import com.loopers.brand.domain.Brand;
import com.loopers.like.domain.Like;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class ProductUseCaseIntegrationTest {
    @Autowired private ProductUseCase useCase;
    @Autowired private EntityManager entityManager;

    @DisplayName("[R-ADMIN-04] 관리자는 상품을 생성·목록 조회·상세 조회·수정·삭제할 수 있다.")
    @Nested class ProductCrud {
        @DisplayName("[상태 전이] 상품 생성·목록·상세·수정·삭제 결과를 저장한다.")
        @Test void persistsEveryProductStateTransition() {
            Brand brand = persist(new Brand("Nike"));
            Product created = useCase.create(brand.getId(), "Air", 1_000L);
            List<Product> listed = useCase.findAll(null, 0, 20);
            Product found = useCase.find(created.getId());
            Product updated = useCase.update(created.getId(), "Air Max", 2_000L, brand.getId());
            useCase.delete(created.getId());
            entityManager.flush();
            entityManager.clear();
            Product deleted = entityManager.find(Product.class, created.getId());
            assertAll(
                () -> assertThat(listed).extracting(Product::getId).contains(created.getId()),
                () -> assertThat(found.getId()).isEqualTo(created.getId()),
                () -> assertThat(updated.getName()).isEqualTo("Air Max"),
                () -> assertThat(updated.getPrice()).isEqualTo(2_000L),
                () -> assertThat(deleted.isDeleted()).isTrue()
            );
        }
    }

    @DisplayName("[R-ADMIN-05] 상품은 존재하며 삭제되지 않은 브랜드만 참조할 수 있다.")
    @Nested class RejectUnavailableBrand {
        @DisplayName("[동등 클래스 분할] 존재하지 않는 브랜드로 만들면 BRAND_NOT_FOUND이고 상품은 저장되지 않는다.")
        @Test void rejectsUnknownBrand() {
            CoreException result = assertThrows(CoreException.class,
                () -> useCase.create(999L, "Air", 1_000L));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND);
            assertThat(entityManager.createQuery("select p from Product p", Product.class).getResultList())
                .isEmpty();
        }

        @DisplayName("[동등 클래스 분할] 삭제된 브랜드로 만들면 BRAND_NOT_FOUND이고 상품은 저장되지 않는다.")
        @Test void rejectsDeletedBrand() {
            Brand deleted = new Brand("Nike");
            deleted.delete();
            persist(deleted);
            CoreException result = assertThrows(CoreException.class,
                () -> useCase.create(deleted.getId(), "Air", 1_000L));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND);
            assertThat(entityManager.createQuery("select p from Product p", Product.class).getResultList())
                .isEmpty();
        }
    }

    @DisplayName("[R-ADMIN-08] 관리자는 상품의 최종 재고 수량을 0 이상으로 설정할 수 있다.")
    @Nested class ChangeStock {
        @DisplayName("[경계값 분석] 최솟값 0으로 변경하면 저장 재고가 0이다.")
        @Test void persistsZeroStock() {
            Product product = new Product(1L, "Air", 1_000L);
            product.changeStock(5);
            persist(product);
            useCase.changeStock(product.getId(), 0);
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Product.class, product.getId()).getStock().quantity()).isZero();
        }
    }

    @DisplayName("[R-LIKE-05] 상품의 좋아요 수는 좋아요 관계에서 조회한다.")
    @Nested class CountLikesFromRelations {
        @DisplayName("[동등 클래스 분할] 관계 둘이 있는 상품의 조회 좋아요 수는 2다.")
        @Test void composesLikeCountFromRelations() {
            Product product = persist(new Product(1L, "Air", 1_000L));
            persist(new Like(1L, product.getId()));
            persist(new Like(2L, product.getId()));
            List<ProductUseCase.ProductView> result = useCase.findCustomerProducts(
                null, ProductSort.LATEST, 0, 20);
            assertThat(result).singleElement().satisfies(view ->
                assertThat(view.likeCount()).isEqualTo(2L));
        }
    }

    @DisplayName("[P-ADMIN-02] 같은 브랜드의 삭제되지 않은 상품끼리는 같은 이름을 허용하지 않는다.")
    @Nested class RejectDuplicatedProductName {
        @DisplayName("[의사결정표] 같은 브랜드에 활성 동명 상품이 있으면 DUPLICATE_PRODUCT_NAME이고 하나만 남는다.")
        @Test void rejectsActiveDuplicateInSameBrand() {
            Brand brand = persist(new Brand("Nike"));
            persist(new Product(brand.getId(), "Air", 1_000L));
            CoreException result = assertThrows(CoreException.class,
                () -> useCase.create(brand.getId(), "Air", 2_000L));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_PRODUCT_NAME);
            assertThat(entityManager.createQuery("select p from Product p", Product.class).getResultList())
                .hasSize(1);
        }

        @DisplayName("[동등 클래스 분할] 앞뒤 공백을 붙인 이름으로 만들면 공백을 뺀 이름으로 저장한다.")
        @Test void savesTrimmedName() {
            Brand brand = persist(new Brand("Nike"));
            Product result = useCase.create(brand.getId(), "  Air  ", 1_000L);
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Product.class, result.getId()).getName()).isEqualTo("Air");
        }

        @DisplayName("[동등 클래스 분할] 같은 브랜드의 활성 상품과 앞뒤 공백만 다른 이름이면 DUPLICATE_PRODUCT_NAME이고 하나만 남는다.")
        @Test void rejectsNameDifferentOnlyByOuterSpaces() {
            Brand brand = persist(new Brand("Nike"));
            persist(new Product(brand.getId(), "Air", 1_000L));
            CoreException result = assertThrows(CoreException.class,
                () -> useCase.create(brand.getId(), "  Air  ", 2_000L));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_PRODUCT_NAME);
            assertThat(entityManager.createQuery("select p from Product p", Product.class).getResultList())
                .hasSize(1);
        }

        @DisplayName("[동등 클래스 분할] 같은 브랜드의 활성 상품과 대소문자만 다른 이름이면 다른 이름으로 저장해 두 상품이 남는다.")
        @Test void savesNameDifferentOnlyByCase() {
            Brand brand = persist(new Brand("Nike"));
            persist(new Product(brand.getId(), "Air", 1_000L));
            useCase.create(brand.getId(), "AIR", 2_000L);
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.createQuery("select p.name from Product p", String.class).getResultList())
                .containsExactlyInAnyOrder("Air", "AIR");
        }

        @DisplayName("[동등 클래스 분할] 같은 브랜드의 다른 활성 상품과 대소문자만 다른 이름으로 수정하면 새 이름이 저장된다.")
        @Test void updatesToNameDifferentOnlyByCase() {
            Brand brand = persist(new Brand("Nike"));
            persist(new Product(brand.getId(), "Air", 1_000L));
            Product target = persist(new Product(brand.getId(), "Max", 2_000L));
            useCase.update(target.getId(), "air", 2_000L, brand.getId());
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Product.class, target.getId()).getName()).isEqualTo("air");
        }
    }

    @DisplayName("[P-ADMIN-05] 상품은 재고 0에서 시작하고 재고 변경으로만 수량을 정한다.")
    @Nested class StartWithZeroStock {
        @DisplayName("[상태 전이] 상품 생성 직후 저장 재고는 0이다.")
        @Test void createsProductWithZeroStock() {
            Brand brand = persist(new Brand("Nike"));
            Product result = useCase.create(brand.getId(), "Air", 1_000L);
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Product.class, result.getId()).getStock().quantity()).isZero();
        }
    }

    @DisplayName("[P-CATALOG-03] 정렬을 지정하지 않으면 상품 목록을 최신순으로 보여 준다.")
    @Nested class DefaultLatestSort {
        @DisplayName("[동등 클래스 분할] 정렬값이 없으면 나중에 등록한 상품이 먼저다.")
        @Test void usesLatestWhenSortIsAbsent() {
            Product older = persist(new Product(1L, "Older", 1_000L));
            Product newer = persist(new Product(1L, "Newer", 2_000L));
            List<ProductUseCase.ProductView> result = useCase.findCustomerProducts(null, null, 0, 20);
            assertAll(
                () -> assertThat(result.getFirst().product().getId()).isEqualTo(newer.getId()),
                () -> assertThat(result.getLast().product().getId()).isEqualTo(older.getId())
            );
        }
    }

    @DisplayName("[P-CATALOG-08] 없거나 삭제된 브랜드로 상품 목록을 거르면 빈 목록을 제공한다.")
    @Nested class EmptyForUnavailableBrandFilter {
        @DisplayName("[동등 클래스 분할] 존재하지 않는 브랜드 식별자의 결과는 빈 목록이다.")
        @Test void returnsEmptyForUnknownBrand() {
            assertThat(useCase.findCustomerProducts(999L, ProductSort.LATEST, 0, 20)).isEmpty();
        }

        @DisplayName("[상태 전이] 삭제된 브랜드 식별자의 결과도 빈 목록이다.")
        @Test void returnsEmptyForDeletedBrand() {
            Brand brand = persist(new Brand("Nike"));
            persist(new Product(brand.getId(), "Air", 1_000L));
            brand.delete();
            entityManager.flush();

            assertThat(useCase.findCustomerProducts(
                brand.getId(), ProductSort.LATEST, 0, 20)).isEmpty();
        }
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
