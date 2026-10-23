package com.loopers.application.like;

import com.loopers.application.product.dto.ProductInfo;
import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.LikeService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class LikeFacadeTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUser() {
        return userRepository.save(User.create());
    }

    private Product saveProduct(String name) {
        return exposedProducts.save(Product.create(1L, name, new Money(89_000), new Stock(10)));
    }

    private Product saveDeletedProduct(String name) {
        Product product = Product.create(1L, name, new Money(89_000), new Stock(10));
        product.delete();
        return productRepository.save(product);
    }

    private ErrorType errorTypeOf(Runnable action) {
        return assertThrows(CoreException.class, action::run).getErrorType();
    }

    @DisplayName("좋아요를 등록할 때,")
    @Nested
    class Like {
        @DisplayName("관계를 저장한다.")
        @Test
        void savesLike() {
            // arrange
            User user = saveUser();
            Product product = saveProduct("러닝화");

            // act
            likeFacade.like(user.getId(), product.getId());

            // assert
            assertThat(likeRepository.exists(user.getId(), product.getId())).isTrue();
        }

        @DisplayName("없거나 삭제된 상품이면 NOT_FOUND 로 거절하고, 저장하지 않는다.")
        @Test
        void throwsNotFound_andSavesNothing_whenProductDoesNotExistOrIsDeleted() {
            // arrange
            User user = saveUser();
            Product deleted = saveDeletedProduct("삭제됨");

            // act & assert
            assertThat(errorTypeOf(() -> likeFacade.like(user.getId(), 999L))).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(errorTypeOf(() -> likeFacade.like(user.getId(), deleted.getId())))
                .isEqualTo(ErrorType.NOT_FOUND);
            assertThat(likeRepository.exists(user.getId(), deleted.getId())).isFalse();
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    class Unlike {
        @DisplayName("관계를 지운다.")
        @Test
        void removesLike() {
            // arrange
            User user = saveUser();
            Product product = saveProduct("러닝화");
            likeFacade.like(user.getId(), product.getId());

            // act
            likeFacade.unlike(user.getId(), product.getId());

            // assert
            assertThat(likeRepository.exists(user.getId(), product.getId())).isFalse();
        }

        @DisplayName("상품이 삭제된 뒤에도 자신의 기존 좋아요는 취소할 수 있다.")
        @Test
        void removesLike_evenAfterProductIsDeleted() {
            // arrange
            User user = saveUser();
            Product product = saveProduct("러닝화");
            likeFacade.like(user.getId(), product.getId());
            product.delete();
            productRepository.save(product);

            // act
            likeFacade.unlike(user.getId(), product.getId());

            // assert
            assertThat(likeRepository.exists(user.getId(), product.getId())).isFalse();
        }
    }

    @DisplayName("내 좋아요 목록을 조회할 때,")
    @Nested
    class GetMyLikes {
        @DisplayName("내가 좋아요한 상품만, 삭제된 상품은 빼고, 최근에 좋아요한 순으로 반환한다.")
        @Test
        void returnsMyLikedProducts_withoutDeleted_newestLikeFirst() {
            // arrange
            User me = saveUser();
            User other = saveUser();
            Product first = saveProduct("첫째");
            Product second = saveProduct("둘째");
            Product removed = saveProduct("삭제 예정");
            Product notMine = saveProduct("남의 것");
            likeFacade.like(me.getId(), second.getId());
            likeFacade.like(me.getId(), removed.getId());
            likeFacade.like(me.getId(), first.getId());
            likeFacade.like(other.getId(), notMine.getId());
            removed.delete();
            productRepository.save(removed);

            // act
            Page<ProductInfo> page = likeFacade.getMyLikes(me.getId(), me.getId(), 0, 20);

            // assert
            assertThat(page.getContent()).extracting(ProductInfo::id)
                .containsExactly(first.getId(), second.getId());
            assertThat(page.getTotalElements()).isEqualTo(2);
        }

        @DisplayName("다른 사용자의 userId 로 조회하면 FORBIDDEN 으로 거절한다. 존재하지 않는 userId 여도 마찬가지다.")
        @Test
        void throwsForbidden_whenUserIdDiffersFromRequester() {
            // arrange
            User me = saveUser();
            User other = saveUser();

            // act & assert
            assertThat(errorTypeOf(() -> likeFacade.getMyLikes(me.getId(), other.getId(), 0, 20)))
                .isEqualTo(ErrorType.FORBIDDEN);
            assertThat(errorTypeOf(() -> likeFacade.getMyLikes(me.getId(), 999L, 0, 20)))
                .isEqualTo(ErrorType.FORBIDDEN);
        }
    }
}
