package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class LikeFacadeIntegrationTest {

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.create());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요를 등록할 때,")
    @Nested
    class Add {
        @DisplayName("없는 User면 NOT_FOUND 예외가 발생하고 Like를 저장하지 않는다.")
        @Test
        void throwsException_whenUserDoesNotExist() {
            // arrange
            Product product = saveProduct();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeFacade.add(999L, product.getId());
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                () -> assertThat(likeRepository.countByProductId(product.getId())).isZero()
            );
        }

        @DisplayName("활성 Product면 Like를 저장하고, 같은 요청을 반복해도 관계를 하나만 유지한다.")
        @Test
        void keepsOneLike_whenRequestIsRepeated() {
            // arrange
            Product product = saveProduct();

            // act
            LikeInfo first = likeFacade.add(user.getId(), product.getId());
            LikeInfo second = likeFacade.add(user.getId(), product.getId());

            // assert
            assertAll(
                () -> assertThat(first.liked()).isTrue(),
                () -> assertThat(second.liked()).isTrue(),
                () -> assertThat(likeRepository.countByProductId(product.getId())).isEqualTo(1L),
                () -> assertThat(likeFacade.countByProductId(product.getId())).isEqualTo(1L)
            );
        }

        @DisplayName("삭제된 Product면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsException_whenProductIsDeleted() {
            // arrange
            Product product = saveProduct();
            product.delete();
            productRepository.save(product);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeFacade.add(user.getId(), product.getId());
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    class Cancel {
        @DisplayName("삭제된 Product에 남은 자신의 Like도 취소한다.")
        @Test
        void cancelsLike_whenProductIsDeleted() {
            // arrange
            Product product = saveProduct();
            likeFacade.add(user.getId(), product.getId());
            product.delete();
            productRepository.save(product);

            // act
            LikeInfo result = likeFacade.cancel(user.getId(), product.getId());

            // assert
            assertAll(
                () -> assertThat(result.liked()).isFalse(),
                () -> assertThat(likeRepository.countByProductId(product.getId())).isZero()
            );
        }
    }

    private Product saveProduct() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        return productRepository.save(Product.create(brand.getId(), "Air Max", 100_000L));
    }
}
