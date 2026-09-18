package com.loopers.brand.application;

import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
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
class BrandUseCaseIntegrationTest {
    @Autowired private BrandUseCase useCase;
    @Autowired private EntityManager entityManager;

    @DisplayName("[R-ADMIN-01] 관리자는 브랜드를 생성·목록 조회·상세 조회·수정·삭제할 수 있다.")
    @Nested class BrandCrud {
        @DisplayName("[상태 전이] 브랜드를 생성하고 수정한 뒤 삭제하면 각 상태가 저장된다.")
        @Test void persistsEveryBrandStateTransition() {
            Brand created = useCase.create("Nike");
            List<Brand> listed = useCase.findAll(0, 20);
            Brand found = useCase.find(created.getId());
            Brand updated = useCase.update(created.getId(), "Jordan");
            useCase.delete(created.getId());
            entityManager.flush();
            entityManager.clear();
            Brand deleted = entityManager.find(Brand.class, created.getId());
            assertAll(
                () -> assertThat(listed).extracting(Brand::getId).contains(created.getId()),
                () -> assertThat(found.getId()).isEqualTo(created.getId()),
                () -> assertThat(updated.getName()).isEqualTo("Jordan"),
                () -> assertThat(deleted.isDeleted()).isTrue()
            );
        }
    }

    @DisplayName("[R-ADMIN-02] 삭제되지 않은 상품이 연결된 브랜드는 삭제할 수 없다.")
    @Nested class RejectBrandDeletionWithProduct {
        @DisplayName("[의사결정표] 활성 상품이 연결되어 있으면 BRAND_HAS_PRODUCTS이고 브랜드를 유지한다.")
        @Test void rejectsDeletionAndKeepsBrand() {
            Brand brand = persist(new Brand("Nike"));
            persist(new Product(brand.getId(), "Air", 1_000L));
            CoreException result = assertThrows(CoreException.class, () -> useCase.delete(brand.getId()));
            entityManager.clear();
            Brand persisted = entityManager.find(Brand.class, brand.getId());
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_HAS_PRODUCTS),
                () -> assertThat(persisted.isDeleted()).isFalse()
            );
        }
    }

    @DisplayName("[R-ADMIN-15] 관리자가 저장하는 브랜드 정보는 유효해야 한다.")
    @Nested class SaveValidBrand {
        @DisplayName("[동등 클래스 분할] 유효한 브랜드 이름은 저장한다.")
        @Test void savesValidBrand() {
            Brand result = useCase.create("Nike");
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Brand.class, result.getId()).getName()).isEqualTo("Nike");
        }
    }

    @DisplayName("[P-ADMIN-01] 삭제되지 않은 브랜드끼리는 같은 이름을 허용하지 않는다.")
    @Nested class RejectDuplicatedBrandName {
        @DisplayName("[의사결정표] 같은 이름의 활성 브랜드가 있으면 DUPLICATE_BRAND_NAME이고 하나만 남는다.")
        @Test void rejectsActiveDuplicateAndKeepsExistingBrand() {
            persist(new Brand("Nike"));
            CoreException result = assertThrows(CoreException.class, () -> useCase.create("Nike"));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_BRAND_NAME);
            assertThat(entityManager.createQuery("select b from Brand b", Brand.class).getResultList())
                .hasSize(1);
        }

        @DisplayName("[동등 클래스 분할] 앞뒤 공백을 붙인 이름으로 만들면 공백을 뺀 이름으로 저장한다.")
        @Test void savesTrimmedName() {
            Brand result = useCase.create("  Nike  ");
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Brand.class, result.getId()).getName()).isEqualTo("Nike");
        }

        @DisplayName("[동등 클래스 분할] 활성 브랜드와 앞뒤 공백만 다른 이름이면 DUPLICATE_BRAND_NAME이고 하나만 남는다.")
        @Test void rejectsNameDifferentOnlyByOuterSpaces() {
            persist(new Brand("Nike"));
            CoreException result = assertThrows(CoreException.class, () -> useCase.create("  Nike  "));
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_BRAND_NAME);
            assertThat(entityManager.createQuery("select b from Brand b", Brand.class).getResultList())
                .hasSize(1);
        }

        @DisplayName("[동등 클래스 분할] 활성 브랜드와 대소문자만 다른 이름이면 다른 이름으로 저장해 두 브랜드가 남는다.")
        @Test void savesNameDifferentOnlyByCase() {
            persist(new Brand("Nike"));
            useCase.create("NIKE");
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.createQuery("select b.name from Brand b", String.class).getResultList())
                .containsExactlyInAnyOrder("Nike", "NIKE");
        }

        @DisplayName("[동등 클래스 분할] 다른 활성 브랜드와 대소문자만 다른 이름으로 수정하면 새 이름이 저장된다.")
        @Test void updatesToNameDifferentOnlyByCase() {
            persist(new Brand("Nike"));
            Brand target = persist(new Brand("Puma"));
            useCase.update(target.getId(), "nike");
            entityManager.flush();
            entityManager.clear();
            assertThat(entityManager.find(Brand.class, target.getId()).getName()).isEqualTo("nike");
        }
    }

    @DisplayName("[P-ADMIN-06] 이미 삭제된 브랜드를 다시 삭제하면 없는 대상으로 거절한다.")
    @Nested class RejectRepeatedDeletion {
        @DisplayName("[상태 전이] 삭제된 브랜드의 재삭제는 BRAND_NOT_FOUND이고 삭제 상태를 유지한다.")
        @Test void rejectsRepeatedDeletionAndKeepsDeletedState() {
            Brand deleted = new Brand("Nike");
            deleted.delete();
            persist(deleted);
            CoreException result = assertThrows(CoreException.class, () -> useCase.delete(deleted.getId()));
            entityManager.clear();
            Brand persisted = entityManager.find(Brand.class, deleted.getId());
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(persisted.isDeleted()).isTrue()
            );
        }
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
