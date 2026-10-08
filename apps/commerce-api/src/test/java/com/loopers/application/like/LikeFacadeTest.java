package com.loopers.application.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loopers.application.product.query.ProductView;
import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class LikeFacadeTest {
    private final ProductLikeRepository likes = mock(ProductLikeRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);

    @Test
    void 등록하면_요청자와_상품_관계의_저장을_요청한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        Product product = Product.restore(10, 1, "상품", 1_000, 0, false);
        when(products.findById(10)).thenReturn(Optional.of(product));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        facade.create(1L, 10);

        // assert
        verify(likes).registerIfAbsent(new ProductLike(1, 10));
    }

    @Test
    void 저장소의_등록이_정상_완료되면_좋아요_등록은_성공한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        Product product = Product.restore(10, 1, "상품", 1_000, 0, false);
        when(products.findById(10)).thenReturn(Optional.of(product));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        assertDoesNotThrow(() -> facade.create(1L, 10));

        // assert
        verify(likes).registerIfAbsent(new ProductLike(1, 10));
    }

    @Test
    void 취소는_요청한_사용자와_상품_ID로_삭제를_요청한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);

        // act
        facade.delete(2L, 10);

        // assert
        verify(likes).delete(2, 10);
    }

    @Test
    void 저장소의_삭제가_정상_완료되면_취소는_성공한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);

        // act
        assertDoesNotThrow(() -> facade.delete(1L, 10));

        // assert
        verify(likes).delete(1, 10);
    }

    @Test
    void 삭제된_상품은_등록을_거절한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        Product product = Product.restore(10, 1, "상품", 1_000, 0, true);
        when(products.findById(10)).thenReturn(Optional.of(product));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(1L, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        verify(likes, never()).registerIfAbsent(any(ProductLike.class));
        verify(likes, never()).delete(1, 10);
    }

    @Test
    void 유효한_사용자는_좋아요_취소를_요청할_수_있다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);

        // act
        facade.delete(1L, 10);

        // assert
        verify(likes).delete(1, 10);
    }

    @Test
    void 없는_상품은_등록할_수_없다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        when(products.findById(999)).thenReturn(Optional.empty());
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(1L, 999));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        verify(likes, never()).registerIfAbsent(any(ProductLike.class));
    }

    @Test
    void 없는_사용자는_등록할_수_없다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(999L, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.USER_NOT_FOUND);
        verify(likes, never()).registerIfAbsent(any(ProductLike.class));
    }

    @Test
    void 식별_누락은_등록할_수_없다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(null, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        verify(likes, never()).registerIfAbsent(any(ProductLike.class));
    }

    @Test
    void 본인의_좋아요_목록을_반환한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        ProductView expected =
                new ProductView(10, "상품", 1_000, new ProductView.BrandView(1, "브랜드"), 1);
        GetLikeFacade facade =
                new GetLikeFacade(users, id -> id == 1 ? List.of(expected) : List.of());

        // act
        var result = facade.get(1L, 1);

        // assert
        assertThat(result).containsExactly(expected);
    }

    @Test
    void 본인_좋아요가_없으면_빈_목록을_반환한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        GetLikeFacade facade = new GetLikeFacade(users, id -> List.of());

        // act
        var result = facade.get(1L, 1);

        // assert
        assertThat(result).isEmpty();
    }

    @Test
    void 타인_목록_접근은_권한_오류로_거절한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        GetLikeFacade facade = new GetLikeFacade(users, id -> List.of());

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.get(1L, 2));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ACCESS_DENIED);
    }
}
