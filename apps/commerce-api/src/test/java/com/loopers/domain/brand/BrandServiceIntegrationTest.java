package com.loopers.domain.brand;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("BrandService 는 브랜드를 등록·조회·수정·삭제한다.")
@SpringBootTest
class BrandServiceIntegrationTest {

    @Autowired
    private BrandService brandService;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("등록")
    @Nested
    class Create {
        @DisplayName("앞뒤 공백을 제거한 이름으로 저장한다.")
        @Test
        void savesWithTrimmedName() {
            BrandModel created = brandService.create("  나이키  ");

            assertAll(
                () -> assertThat(created.getId()).isNotNull(),
                () -> assertThat(created.getName()).isEqualTo("나이키"),
                () -> assertThat(brandJpaRepository.findById(created.getId()).orElseThrow().getName()).isEqualTo("나이키")
            );
        }

        @DisplayName("이름이 비어 있으면 INVALID_BRAND_NAME 으로 거절하고 저장하지 않는다.")
        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> brandService.create("   "))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.INVALID_BRAND_NAME);
            assertThat(brandJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("상세 조회")
    @Nested
    class GetBrand {
        @DisplayName("삭제되지 않은 브랜드를 반환한다.")
        @Test
        void returnsActiveBrand() {
            BrandModel nike = brandFixture.createBrand("나이키");

            BrandModel found = brandService.getBrand(nike.getId());

            assertThat(found.getName()).isEqualTo("나이키");
        }

        @DisplayName("존재하지 않는 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownBrand() {
            assertThatThrownBy(() -> brandService.getBrand(999L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            assertThatThrownBy(() -> brandService.getBrand(deleted.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }
    }

    @DisplayName("수정")
    @Nested
    class Update {
        @DisplayName("이름을 변경해 저장한다.")
        @Test
        void changesName() {
            BrandModel nike = brandFixture.createBrand("나이키");

            BrandModel updated = brandService.update(nike.getId(), "나이키 코리아");

            assertAll(
                () -> assertThat(updated.getName()).isEqualTo("나이키 코리아"),
                () -> assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getName())
                    .isEqualTo("나이키 코리아")
            );
        }

        @DisplayName("잘못된 이름으로는 기존 이름을 바꾸지 않는다.")
        @Test
        void keepsNameWhenInvalid() {
            BrandModel nike = brandFixture.createBrand("나이키");

            assertThatThrownBy(() -> brandService.update(nike.getId(), ""))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.INVALID_BRAND_NAME);
            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getName()).isEqualTo("나이키");
        }

        @DisplayName("삭제된 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            assertThatThrownBy(() -> brandService.update(deleted.getId(), "새이름"))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }
    }

    @DisplayName("삭제")
    @Nested
    class Delete {
        @DisplayName("연결된 활성 상품이 없으면 삭제 시각을 기록한다.")
        @Test
        void deletesWhenNoActiveProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");

            brandService.delete(nike.getId());

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("재고가 0인 활성 상품이 남아 있으면 BRAND_HAS_ACTIVE_PRODUCTS 로 거절하고 브랜드를 유지한다.")
        @Test
        void rejectsWhenActiveProductRemains() {
            BrandModel nike = brandFixture.createBrand("나이키");
            productFixture.createProduct(nike.getId(), "품절 운동화", 10_000L, 0L);

            assertThatThrownBy(() -> brandService.delete(nike.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_HAS_ACTIVE_PRODUCTS);
            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNull();
        }

        @DisplayName("연결된 상품이 모두 삭제되었다면 삭제한다.")
        @Test
        void deletesWhenAllProductsDeleted() {
            BrandModel nike = brandFixture.createBrand("나이키");
            var shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);
            productFixture.deleteProduct(shoes.getId());

            brandService.delete(nike.getId());

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("다른 브랜드의 활성 상품은 삭제를 막지 않는다.")
        @Test
        void ignoresOtherBrandProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 20_000L, 5L);

            brandService.delete(nike.getId());

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("이미 삭제된 브랜드를 다시 삭제하면 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            assertThatThrownBy(() -> brandService.delete(deleted.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }
    }

    @DisplayName("목록 조회")
    @Nested
    class GetBrands {
        @DisplayName("삭제된 브랜드를 제외하고 latest 는 생성 시각 내림차순으로 반환한다.")
        @Test
        void returnsActiveBrandsLatestFirst() {
            brandFixture.createBrand("첫째");
            brandFixture.createBrand("둘째");
            brandFixture.createDeletedBrand("삭제됨");
            brandFixture.createBrand("셋째");

            PageResult<BrandModel> result = brandService.getBrands(PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.items()).extracting(BrandModel::getName)
                    .containsExactly("셋째", "둘째", "첫째")
            );
        }

        @DisplayName("oldest 는 생성 시각 오름차순으로 반환한다.")
        @Test
        void returnsOldestFirst() {
            brandFixture.createBrand("첫째");
            brandFixture.createBrand("둘째");
            brandFixture.createBrand("셋째");

            PageResult<BrandModel> result = brandService.getBrands(PageCommand.of(null, null), ListSort.OLDEST);

            assertThat(result.items()).extracting(BrandModel::getName).containsExactly("첫째", "둘째", "셋째");
        }

        @DisplayName("요청한 페이지 크기만큼 끊어 전체 개수와 함께 반환한다.")
        @Test
        void returnsRequestedPage() {
            List.of("첫째", "둘째", "셋째", "넷째", "다섯째").forEach(brandFixture::createBrand);

            PageResult<BrandModel> result = brandService.getBrands(PageCommand.of(1, 2), ListSort.OLDEST);

            assertAll(
                () -> assertThat(result.page()).isEqualTo(1),
                () -> assertThat(result.size()).isEqualTo(2),
                () -> assertThat(result.totalElements()).isEqualTo(5L),
                () -> assertThat(result.totalPages()).isEqualTo(3),
                () -> assertThat(result.items()).extracting(BrandModel::getName).containsExactly("셋째", "넷째")
            );
        }
    }
}
