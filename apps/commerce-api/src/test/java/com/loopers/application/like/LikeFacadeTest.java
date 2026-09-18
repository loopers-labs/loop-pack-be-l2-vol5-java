package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LikeFacadeTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductService productService;

    @Mock
    private LikeService likeService;

    @InjectMocks
    private LikeFacade likeFacade;

    @DisplayName("좋아요를 등록할 때, ")
    @Nested
    class Like_ {
        @DisplayName("사용자와 상품이 유효하면, 좋아요 등록에 위임한다.")
        @Test
        void delegatesToLikeService_whenUserAndProductAreValid() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(2L)).willReturn(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));
            given(likeService.like(1L, 2L)).willReturn(true);

            // act
            boolean created = likeFacade.like(1L, 2L);

            // assert
            assertThat(created).isTrue();
            verify(likeService).like(1L, 2L);
        }

        @DisplayName("존재하지 않는 사용자면, UNAUTHORIZED 예외가 발생하고 등록되지 않는다.")
        @Test
        void throwsUnauthorizedException_whenUserIsAbsent() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(false);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeFacade.like(1L, 2L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
            verify(likeService, never()).like(anyLong(), anyLong());
        }

        @DisplayName("없거나 삭제된 상품이면, NOT_FOUND 예외가 전파되고 등록되지 않는다.")
        @Test
        void propagatesNotFoundException_whenProductIsAbsentOrDeleted() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(productService.getActiveProduct(2L))
                .willThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeFacade.like(1L, 2L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(likeService, never()).like(anyLong(), anyLong());
        }
    }

    @DisplayName("좋아요를 취소할 때, ")
    @Nested
    class Unlike {
        @DisplayName("사용자가 유효하면, 좋아요 취소에 위임한다.")
        @Test
        void delegatesToLikeService_whenUserIsValid() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);

            // act
            likeFacade.unlike(1L, 2L);

            // assert
            verify(likeService).unlike(1L, 2L);
        }

        @DisplayName("삭제된 상품이어도, 기존 좋아요는 취소된다.")
        @Test
        void deletesLike_evenWhenProductIsDeleted() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);

            // act
            likeFacade.unlike(1L, 2L);

            // assert
            verify(productService, never()).getActiveProduct(anyLong());
            verify(likeService).unlike(1L, 2L);
        }

        @DisplayName("존재하지 않는 사용자면, UNAUTHORIZED 예외가 발생한다.")
        @Test
        void throwsUnauthorizedException_whenUserIsAbsent() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(false);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeFacade.unlike(1L, 2L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
            verify(likeService, never()).unlike(anyLong(), anyLong());
        }
    }

    @DisplayName("내 좋아요 목록을 조회할 때, ")
    @Nested
    class GetMyLikes {
        @DisplayName("본인 요청이면, 좋아요한 상품 목록을 반환한다.")
        @Test
        void returnsLikedProducts_whenRequesterIsOwner() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(likeService.getLikes(1L)).willReturn(List.of(new Like(1L, 2L)));
            given(productService.findActiveProducts(List.of(2L)))
                .willReturn(List.of(new Product(1L, "루퍼스 티셔츠", new Price(1000L))));
            given(likeService.countByProductIds(anyList())).willReturn(Map.of(0L, 3L));

            // act
            List<LikeInfo> result = likeFacade.getMyLikes(1L, 1L);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).productName()).isEqualTo("루퍼스 티셔츠");
            assertThat(result.get(0).likeCount()).isEqualTo(3L);
        }

        @DisplayName("본인이 아닌 사용자의 목록을 조회하면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenRequesterIsNotOwner() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeFacade.getMyLikes(1L, 9L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(likeService, never()).getLikes(anyLong());
        }

        @DisplayName("삭제된 상품은 목록에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            given(userRepository.existsById(1L)).willReturn(true);
            given(likeService.getLikes(1L)).willReturn(List.of(new Like(1L, 2L), new Like(1L, 3L)));
            given(productService.findActiveProducts(List.of(2L, 3L)))
                .willReturn(List.of(new Product(1L, "살아있는 상품", new Price(1000L))));
            given(likeService.countByProductIds(anyList())).willReturn(Map.of(0L, 1L));

            // act
            List<LikeInfo> result = likeFacade.getMyLikes(1L, 1L);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).productName()).isEqualTo("살아있는 상품");
        }
    }
}
