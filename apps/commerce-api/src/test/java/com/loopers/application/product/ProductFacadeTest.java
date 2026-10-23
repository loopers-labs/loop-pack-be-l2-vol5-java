package com.loopers.application.product;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.dto.ProductDetailInfo;
import com.loopers.application.product.dto.ProductListItemInfo;
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
import com.loopers.utils.RedisCleanUp;
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

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        // 테이블을 비우면 id 가 다시 1부터 쓰이므로, 이전 테스트의 목록 캐시가 새 데이터로 hit 되지 않게 함께 비운다.
        redisCleanUp.truncateAll();
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

    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class GetList {
        private List<Long> ids(Page<ProductListItemInfo> page) {
            return page.getContent().stream().map(info -> info.id()).toList();
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
            Page<ProductListItemInfo> page = productFacade.getList(null, "latest", 0, 20);

            // assert: 최신순이라 cap, bag, shoes
            assertThat(page.getContent()).extracting(info -> info.brand().name())
                .containsExactly("제로스", "제로스", "루퍼스");
            assertThat(page.getContent()).extracting(ProductListItemInfo::likeCount)
                .containsExactly(0L, 1L, 2L);
            assertThat(page.getContent()).extracting(info -> info.id())
                .containsExactly(cap.getId(), bag.getId(), shoes.getId());
        }

        @DisplayName("상품이 없어도 빈 목록으로 응답한다.")
        @Test
        void returnsEmptyPage_whenNoProducts() {
            // act
            Page<ProductListItemInfo> page = productFacade.getList(null, "likes_desc", 0, 20);

            // assert
            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
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
        // 정상 흐름에서는 상품이 있는 브랜드를 삭제할 수 없다. 브랜드 삭제와 상품 등록이 동시에 일어나는 경쟁으로만
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

    @DisplayName("데이터가 깨져 노출 상품의 좋아요 집계 행이 없을 때,")
    @Nested
    class MissingLikeCount {

        @DisplayName("목록 조회는 0으로 채우지 않고 INTERNAL_ERROR 로 거절하며, 메시지에 상품 id 를 담는다.")
        @Test
        void throwsInternalError_onList() {
            // arrange: 생성 유스케이스를 거치지 않아 집계 행이 없는 노출 상품
            Brand brand = saveBrand("루퍼스");
            Product product = productRepository.save(Product.create(brand.getId(), "러닝화", new Money(89_000), new Stock(10)));

            // act
            CoreException exception = assertThrows(CoreException.class, () -> productFacade.getList(null, "latest", 0, 20));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR);
            assertThat(exception.getCustomMessage()).contains(String.valueOf(product.getId()));
        }
    }
}
