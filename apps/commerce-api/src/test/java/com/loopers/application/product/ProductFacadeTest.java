package com.loopers.application.product;

import com.loopers.application.like.LikeFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductFacadeTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUser() {
        return userRepository.save(User.create());
    }

    private Brand saveBrand(String name) {
        return brandRepository.save(Brand.create(name));
    }

    private Product saveProduct(Brand brand, String name, long price) {
        return exposedProducts.save(Product.create(brand.getId(), name, new Money(price), new Stock(10)));
    }

    private Product saveDeletedProduct(Brand brand, String name) {
        Product product = Product.create(brand.getId(), name, new Money(1_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private ErrorType errorTypeOf(Runnable action) {
        return assertThrows(CoreException.class, action::run).getErrorType();
    }

    @DisplayName("상품 상세를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("상품 정보에 브랜드 정보와 좋아요 수를 조합해 반환한다.")
        @Test
        void composesProductBrandAndLikeCount() {
            // arrange
            User me = saveUser();
            User other = saveUser();
            Brand brand = saveBrand("루퍼스");
            Product product = saveProduct(brand, "러닝화", 89_000);
            likeFacade.like(me.getId(), product.getId());
            likeFacade.like(other.getId(), product.getId());

            // act
            ProductDetailInfo info = productFacade.get(product.getId());

            // assert
            assertThat(info.product().id()).isEqualTo(product.getId());
            assertThat(info.product().name()).isEqualTo("러닝화");
            assertThat(info.product().price()).isEqualTo(89_000);
            assertThat(info.brand().id()).isEqualTo(brand.getId());
            assertThat(info.brand().name()).isEqualTo("루퍼스");
            assertThat(info.likeCount()).isEqualTo(2);
        }

        @DisplayName("좋아요를 등록하고 취소하면 좋아요 수에 반영된다.")
        @Test
        void reflectsLikeAndUnlikeInCount() {
            // arrange
            User me = saveUser();
            Product product = saveProduct(saveBrand("루퍼스"), "러닝화", 89_000);
            assertThat(productFacade.get(product.getId()).likeCount()).isZero();

            // act & assert
            likeFacade.like(me.getId(), product.getId());
            assertThat(productFacade.get(product.getId()).likeCount()).isEqualTo(1);
            likeFacade.unlike(me.getId(), product.getId());
            assertThat(productFacade.get(product.getId()).likeCount()).isZero();
        }

        @DisplayName("관리자가 가격을 수정하면 조회에 반영된다.")
        @Test
        void reflectsAdminUpdate() {
            // arrange
            Product product = saveProduct(saveBrand("루퍼스"), "러닝화", 89_000);

            // act
            adminProductFacade.update(product.getId(), "러닝화", 99_000);

            // assert
            assertThat(productFacade.get(product.getId()).product().price()).isEqualTo(99_000);
        }

        @DisplayName("없거나 삭제된 상품이면 NOT_FOUND 로 거절한다.")
        @Test
        void throwsNotFound_whenProductDoesNotExistOrIsDeleted() {
            // arrange
            Product deleted = saveDeletedProduct(saveBrand("루퍼스"), "삭제됨");

            // act & assert
            assertThat(errorTypeOf(() -> productFacade.get(999L))).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(errorTypeOf(() -> productFacade.get(deleted.getId())))
                .isEqualTo(ErrorType.NOT_FOUND);
        }

    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class GetList {
        @DisplayName("정렬 기준에 따라 반환한다. (latest: 최신순, price_asc: 가격 낮은 순, likes_desc: 좋아요 많은 순)")
        @Test
        void sortsBySortParameter() {
            // arrange
            User me = saveUser();
            User other = saveUser();
            Brand brand = saveBrand("루퍼스");
            Product cheapOld = saveProduct(brand, "싸고 오래됨", 1_000);
            Product expensiveMid = saveProduct(brand, "비싸고 인기", 9_000);
            Product midNew = saveProduct(brand, "중간 가격 최신", 5_000);
            likeFacade.like(me.getId(), expensiveMid.getId());
            likeFacade.like(other.getId(), expensiveMid.getId());
            likeFacade.like(me.getId(), midNew.getId());

            // act & assert
            assertThat(ids(productFacade.getList(null, "latest", 0, 20)))
                .containsExactly(midNew.getId(), expensiveMid.getId(), cheapOld.getId());
            assertThat(ids(productFacade.getList(null, "price_asc", 0, 20)))
                .containsExactly(cheapOld.getId(), midNew.getId(), expensiveMid.getId());
            assertThat(ids(productFacade.getList(null, "likes_desc", 0, 20)))
                .containsExactly(expensiveMid.getId(), midNew.getId(), cheapOld.getId());
        }

        private List<Long> ids(Page<ProductDetailInfo> page) {
            return page.getContent().stream().map(info -> info.product().id()).toList();
        }

        @DisplayName("항목마다 자기 브랜드 정보와 좋아요 수를 담는다.")
        @Test
        void attachesBrandAndLikeCountPerItem() {
            // arrange
            User me = saveUser();
            User other = saveUser();
            Brand loopers = saveBrand("루퍼스");
            Brand zeros = saveBrand("제로스");
            Product shoes = saveProduct(loopers, "러닝화", 89_000);
            Product bag = saveProduct(zeros, "가방", 30_000);
            Product cap = saveProduct(zeros, "모자", 15_000);
            likeFacade.like(me.getId(), shoes.getId());
            likeFacade.like(other.getId(), shoes.getId());
            likeFacade.like(me.getId(), bag.getId());

            // act
            Page<ProductDetailInfo> page = productFacade.getList(null, "latest", 0, 20);

            // assert: 최신순이라 cap, bag, shoes
            assertThat(page.getContent()).extracting(info -> info.brand().name())
                .containsExactly("제로스", "제로스", "루퍼스");
            assertThat(page.getContent()).extracting(ProductDetailInfo::likeCount)
                .containsExactly(0L, 1L, 2L);
            assertThat(page.getContent()).extracting(info -> info.product().id())
                .containsExactly(cap.getId(), bag.getId(), shoes.getId());
        }

        @DisplayName("삭제된 상품은 제외한다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product active = saveProduct(brand, "판매 중", 1_000);
            saveDeletedProduct(brand, "삭제됨");

            // act
            Page<ProductDetailInfo> page = productFacade.getList(null, "latest", 0, 20);

            // assert
            assertThat(page.getContent()).extracting(info -> info.product().id()).containsExactly(active.getId());
        }

        @DisplayName("브랜드를 지정하면 그 브랜드의 상품만 반환한다. 존재하지 않는 브랜드이면 빈 목록이다.")
        @Test
        void filtersByBrand() {
            // arrange
            Brand loopers = saveBrand("루퍼스");
            Brand zeros = saveBrand("제로스");
            Product shoes = saveProduct(loopers, "러닝화", 89_000);
            saveProduct(zeros, "가방", 30_000);

            // act
            Page<ProductDetailInfo> filtered = productFacade.getList(loopers.getId(), "latest", 0, 20);
            Page<ProductDetailInfo> unknown = productFacade.getList(999L, "latest", 0, 20);

            // assert
            assertThat(filtered.getContent()).extracting(info -> info.product().id()).containsExactly(shoes.getId());
            assertThat(unknown.getContent()).isEmpty();
            assertThat(unknown.getTotalElements()).isZero();
        }

        @DisplayName("상품이 없어도 빈 목록으로 응답한다.")
        @Test
        void returnsEmptyPage_whenNoProducts() {
            // act
            Page<ProductDetailInfo> page = productFacade.getList(null, "likes_desc", 0, 20);

            // assert
            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
        }

        @DisplayName("페이지 크기만큼만 반환하고, 총 개수와 전체 페이지 수를 계산한다.")
        @Test
        void paginates() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            saveProduct(brand, "하나", 1_000);
            saveProduct(brand, "둘", 1_000);
            saveProduct(brand, "셋", 1_000);

            // act
            Page<ProductDetailInfo> secondPage = productFacade.getList(null, "latest", 1, 2);

            // assert
            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(secondPage.getTotalElements()).isEqualTo(3);
            assertThat(secondPage.getTotalPages()).isEqualTo(2);
        }

        @DisplayName("sort 가 잘못되었거나 page, size 가 범위를 벗어나면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @CsvSource({"abc, 0, 20", "latest, -1, 20", "latest, 0, 0", "latest, 0, 101"})
        void rejectsWithInvalidInput_whenSortPageOrSizeIsInvalid(String sort, int page, int size) {
            // act & assert
            assertThat(errorTypeOf(() -> productFacade.getList(null, sort, page, size)))
                .isEqualTo(ErrorType.INVALID_INPUT);
        }

    }

    @DisplayName("데이터가 깨져 상품의 브랜드를 찾을 수 없을 때,")
    @Nested
    class MissingBrand {
        // 정상 흐름에서는 상품이 있는 브랜드를 삭제할 수 없다. 브랜드 삭제와 상품 등록이 동시에 일어나는 경쟁(D-16)으로만
        // 생길 수 있는 상태라, Facade 의 확인을 거치지 않고 Repository 로 직접 브랜드를 삭제해 만든다.
        private Product saveProductOfDeletedBrand() {
            Brand brand = saveBrand("루퍼스");
            Product product = saveProduct(brand, "러닝화", 89_000);
            brand.delete();
            brandRepository.save(brand);
            return product;
        }

        @DisplayName("상세 조회는 INTERNAL_ERROR 로 거절하고, 메시지에 상품과 브랜드의 id 를 담는다.")
        @Test
        void throwsInternalError_onDetail() {
            // arrange
            Product product = saveProductOfDeletedBrand();

            // act
            CoreException exception = assertThrows(CoreException.class, () -> productFacade.get(product.getId()));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR);
            assertThat(exception.getCustomMessage())
                .contains(String.valueOf(product.getId()))
                .contains(String.valueOf(product.getBrandId()));
        }

        @DisplayName("목록 조회도 INTERNAL_ERROR 로 거절한다. 깨진 상태를 조용히 숨기지 않는다.")
        @Test
        void throwsInternalError_onList() {
            // arrange
            saveProductOfDeletedBrand();

            // act & assert
            assertThat(errorTypeOf(() -> productFacade.getList(null, "latest", 0, 20)))
                .isEqualTo(ErrorType.INTERNAL_ERROR);
        }
    }
}
