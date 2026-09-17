package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BrandFacadeIntegrationTest {

    @Autowired
    private BrandFacade brandFacade;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel admin;

    @BeforeEach
    void setUp() {
        user = fixtures.user();
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-BRAND-01 브랜드 상세 조회")
    class GetBrand {
        @DisplayName("[FR-BRAND-01] 삭제되지 않은 브랜드 정보를 반환한다.")
        @Test
        void returnsBrand() {
            BrandModel brand = fixtures.brand("나이키");

            BrandInfo info = brandFacade.getBrand(user.getId(), brand.getId());

            assertThat(info.id()).isEqualTo(brand.getId());
            assertThat(info.name()).isEqualTo("나이키");
        }

        @DisplayName("[FR-BRAND-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            BrandModel brand = fixtures.brand("나이키");

            assertThrowsErrorType(() -> brandFacade.getBrand(999L, brand.getId()), ErrorType.USER_NOT_FOUND);
        }

        @DisplayName("[FR-BRAND-01 BRAND_NOT_FOUND] 존재하지 않는 브랜드.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> brandFacade.getBrand(user.getId(), 999L), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-BRAND-01 BRAND_NOT_FOUND] 삭제된 브랜드도 같은 실패.")
        @Test
        void throwsBrandNotFound_whenDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> brandFacade.getBrand(user.getId(), deleted.getId()), ErrorType.BRAND_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-01 브랜드 목록")
    class ListBrandsForAdmin {
        @DisplayName("[FR-ADMIN-BRAND-01] 삭제된 브랜드 포함, 최신순, 삭제 여부 표시 (ASM-15).")
        @Test
        void listsAllIncludingDeleted() {
            BrandModel first = fixtures.brand("첫째");
            BrandModel deleted = fixtures.deletedBrand("둘째-삭제");
            BrandModel third = fixtures.brand("셋째");

            PageResult<BrandInfo> page = brandFacade.listBrandsForAdmin(admin.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(3);
            assertThat(page.items()).extracting(BrandInfo::id)
                .containsExactly(third.getId(), deleted.getId(), first.getId());
            assertThat(page.items()).extracting(BrandInfo::deleted).containsExactly(false, true, false);
        }

        @DisplayName("[FR-ADMIN-BRAND-01] 페이지 크기로 잘라 반환한다.")
        @Test
        void pagesResults() {
            for (int i = 0; i < 5; i++) {
                fixtures.brand("브랜드" + i);
            }

            PageResult<BrandInfo> page = brandFacade.listBrandsForAdmin(admin.getId(), PageQuery.of(1, 2));

            assertThat(page.items()).hasSize(2);
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalCount()).isEqualTo(5);
        }

        @DisplayName("[FR-ADMIN-BRAND-01 NOT_ADMIN] 관리자가 아니면 거절.")
        @Test
        void throwsNotAdmin() {
            assertThrowsErrorType(() -> brandFacade.listBrandsForAdmin(user.getId(), PageQuery.of(0, 10)), ErrorType.NOT_ADMIN);
        }

        @DisplayName("[FR-ADMIN-BRAND-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> brandFacade.listBrandsForAdmin(admin.getId(), PageQuery.of(-1, 10)), ErrorType.INVALID_PAGE);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-02 브랜드 생성")
    class CreateBrand {
        @DisplayName("[FR-ADMIN-BRAND-02] 생성되고 상태는 삭제되지 않음. 이후 고객 조회에 즉시 반영 (ASM-21).")
        @Test
        void createsBrand() {
            BrandInfo created = brandFacade.createBrand(admin.getId(), "아디다스");

            assertThat(created.id()).isNotNull();
            assertThat(created.deleted()).isFalse();
            assertThat(brandFacade.getBrand(user.getId(), created.id()).name()).isEqualTo("아디다스");
        }

        @DisplayName("[FR-ADMIN-BRAND-02 INVALID_BRAND][INV-14] 이름이 유효하지 않으면 생성되지 않는다.")
        @Test
        void throwsInvalidBrand() {
            assertThrowsErrorType(() -> brandFacade.createBrand(admin.getId(), " "), ErrorType.INVALID_BRAND);

            assertThat(brandFacade.listBrandsForAdmin(admin.getId(), PageQuery.of(0, 10)).totalCount()).isZero();
        }

        @DisplayName("[FR-ADMIN-BRAND-02 NOT_ADMIN] 관리자가 아니면 생성되지 않는다.")
        @Test
        void throwsNotAdmin() {
            assertThrowsErrorType(() -> brandFacade.createBrand(user.getId(), "아디다스"), ErrorType.NOT_ADMIN);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-03 브랜드 상세 (관리자)")
    class GetBrandForAdmin {
        @DisplayName("[FR-ADMIN-BRAND-03] 삭제된 브랜드도 조회되며 삭제 여부가 표시된다.")
        @Test
        void returnsDeletedBrand() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            BrandInfo info = brandFacade.getBrandForAdmin(admin.getId(), deleted.getId());

            assertThat(info.deleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-BRAND-03 BRAND_NOT_FOUND] 존재하지 않으면 거절.")
        @Test
        void throwsBrandNotFound() {
            assertThrowsErrorType(() -> brandFacade.getBrandForAdmin(admin.getId(), 999L), ErrorType.BRAND_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-04 브랜드 수정")
    class UpdateBrand {
        @DisplayName("[FR-ADMIN-BRAND-04] 이름이 갱신되고 고객 조회에 즉시 반영된다.")
        @Test
        void updatesName() {
            BrandModel brand = fixtures.brand("옛 이름");

            BrandInfo updated = brandFacade.updateBrand(admin.getId(), brand.getId(), "새 이름");

            assertThat(updated.name()).isEqualTo("새 이름");
            assertThat(brandFacade.getBrand(user.getId(), brand.getId()).name()).isEqualTo("새 이름");
        }

        @DisplayName("[FR-ADMIN-BRAND-04 BRAND_NOT_FOUND] 존재하지 않으면 거절.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> brandFacade.updateBrand(admin.getId(), 999L, "새 이름"), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-BRAND-04 BRAND_NOT_FOUND] 삭제된 브랜드는 수정 대상이 아니다. 삭제됨 유지.")
        @Test
        void throwsBrandNotFound_whenDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> brandFacade.updateBrand(admin.getId(), deleted.getId(), "새 이름"), ErrorType.BRAND_NOT_FOUND);

            BrandModel reloaded = fixtures.reloadBrand(deleted.getId());
            assertThat(reloaded.isDeleted()).isTrue();
            assertThat(reloaded.getName()).isEqualTo("삭제됨");
        }

        @DisplayName("[FR-ADMIN-BRAND-04 INVALID_BRAND] 정보가 유효하지 않으면 기존 값 유지.")
        @Test
        void throwsInvalidBrand_keepsOldName() {
            BrandModel brand = fixtures.brand("옛 이름");

            assertThrowsErrorType(() -> brandFacade.updateBrand(admin.getId(), brand.getId(), ""), ErrorType.INVALID_BRAND);

            assertThat(fixtures.reloadBrand(brand.getId()).getName()).isEqualTo("옛 이름");
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-05 브랜드 삭제")
    class DeleteBrand {
        @DisplayName("[FR-ADMIN-BRAND-05][ST-01] 삭제되면 고객 조회에서 제외되고 관리자 조회에는 삭제됨으로 남는다.")
        @Test
        void deletesBrand() {
            BrandModel brand = fixtures.brand("브랜드");

            brandFacade.deleteBrand(admin.getId(), brand.getId());

            assertThrowsErrorType(() -> brandFacade.getBrand(user.getId(), brand.getId()), ErrorType.BRAND_NOT_FOUND);
            assertThat(brandFacade.getBrandForAdmin(admin.getId(), brand.getId()).deleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-BRAND-05][INV-10] 소속 상품이 전부 삭제됐으면 삭제할 수 있다.")
        @Test
        void deletesBrand_whenAllProductsDeleted() {
            BrandModel brand = fixtures.brand("브랜드");
            fixtures.deletedProduct(brand.getId(), "삭제된 상품", 1000L, 0);

            brandFacade.deleteBrand(admin.getId(), brand.getId());

            assertThat(fixtures.reloadBrand(brand.getId()).isDeleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-BRAND-05 BRAND_HAS_PRODUCTS][INV-10] 삭제되지 않은 상품(재고 0 포함)이 있으면 삭제되지 않음 유지.")
        @Test
        void throwsBrandHasProducts() {
            BrandModel brand = fixtures.brand("브랜드");
            fixtures.product(brand.getId(), "재고 없는 상품", 1000L, 0);

            assertThrowsErrorType(() -> brandFacade.deleteBrand(admin.getId(), brand.getId()), ErrorType.BRAND_HAS_PRODUCTS);

            assertThat(fixtures.reloadBrand(brand.getId()).isDeleted()).isFalse();
        }

        @DisplayName("[FR-ADMIN-BRAND-05 BRAND_NOT_FOUND] 존재하지 않으면 거절.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> brandFacade.deleteBrand(admin.getId(), 999L), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-ADMIN-BRAND-05 BRAND_NOT_FOUND] 이미 삭제된 브랜드의 재삭제는 거절 (ASM-18).")
        @Test
        void throwsBrandNotFound_whenAlreadyDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> brandFacade.deleteBrand(admin.getId(), deleted.getId()), ErrorType.BRAND_NOT_FOUND);
        }
    }
}
