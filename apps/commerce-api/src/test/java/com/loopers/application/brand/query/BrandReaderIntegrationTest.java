package com.loopers.application.brand.query;

import com.loopers.domain.brand.BrandModel;
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
class BrandReaderIntegrationTest {

    @Autowired
    private BrandReader brandReader;
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

            BrandView.Summary info = brandReader.getBrand(user.getId(), brand.getId());

            assertThat(info.id()).isEqualTo(brand.getId());
            assertThat(info.name()).isEqualTo("나이키");
        }

        @DisplayName("[FR-BRAND-01 USER_NOT_FOUND] 요청자가 없으면 거절.")
        @Test
        void throwsUserNotFound() {
            BrandModel brand = fixtures.brand("나이키");

            assertThrowsErrorType(() -> brandReader.getBrand(999L, brand.getId()), ErrorType.USER_NOT_FOUND);
        }

        @DisplayName("[FR-BRAND-01 BRAND_NOT_FOUND] 존재하지 않는 브랜드.")
        @Test
        void throwsBrandNotFound_whenMissing() {
            assertThrowsErrorType(() -> brandReader.getBrand(user.getId(), 999L), ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("[FR-BRAND-01 BRAND_NOT_FOUND] 삭제된 브랜드도 같은 실패.")
        @Test
        void throwsBrandNotFound_whenDeleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            assertThrowsErrorType(() -> brandReader.getBrand(user.getId(), deleted.getId()), ErrorType.BRAND_NOT_FOUND);
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

            PageResult<BrandView.Admin> page = brandReader.listBrandsForAdmin(admin.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(3);
            assertThat(page.items()).extracting(BrandView.Admin::id)
                .containsExactly(third.getId(), deleted.getId(), first.getId());
            assertThat(page.items()).extracting(BrandView.Admin::deleted).containsExactly(false, true, false);
        }

        @DisplayName("[FR-ADMIN-BRAND-01] 페이지 크기로 잘라 반환한다.")
        @Test
        void pagesResults() {
            for (int i = 0; i < 5; i++) {
                fixtures.brand("브랜드" + i);
            }

            PageResult<BrandView.Admin> page = brandReader.listBrandsForAdmin(admin.getId(), PageQuery.of(1, 2));

            assertThat(page.items()).hasSize(2);
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalCount()).isEqualTo(5);
        }

        @DisplayName("[FR-ADMIN-BRAND-01 NOT_ADMIN] 관리자가 아니면 거절.")
        @Test
        void throwsNotAdmin() {
            assertThrowsErrorType(() -> brandReader.listBrandsForAdmin(user.getId(), PageQuery.of(0, 10)), ErrorType.NOT_ADMIN);
        }

        @DisplayName("[FR-ADMIN-BRAND-01 INVALID_PAGE] 페이지 값이 잘못되면 거절.")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> brandReader.listBrandsForAdmin(admin.getId(), PageQuery.of(-1, 10)), ErrorType.INVALID_PAGE);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-BRAND-03 브랜드 상세 (관리자)")
    class GetBrandForAdmin {
        @DisplayName("[FR-ADMIN-BRAND-03] 삭제된 브랜드도 조회되며 삭제 여부가 표시된다.")
        @Test
        void returnsDeletedBrand() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            BrandView.Admin info = brandReader.getBrandForAdmin(admin.getId(), deleted.getId());

            assertThat(info.deleted()).isTrue();
        }

        @DisplayName("[FR-ADMIN-BRAND-03 BRAND_NOT_FOUND] 존재하지 않으면 거절.")
        @Test
        void throwsBrandNotFound() {
            assertThrowsErrorType(() -> brandReader.getBrandForAdmin(admin.getId(), 999L), ErrorType.BRAND_NOT_FOUND);
        }
    }
}
