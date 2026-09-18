package com.loopers.domain.like;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock
    private LikeRepository likeRepository;

    @InjectMocks
    private LikeService likeService;

    @DisplayName("좋아요를 등록할 때, ")
    @Nested
    class Like_ {
        @DisplayName("기존 관계가 없으면, 새 관계를 저장한다.")
        @Test
        void savesLike_whenRelationIsAbsent() {
            // arrange
            given(likeRepository.findByUserIdAndProductId(1L, 2L)).willReturn(Optional.empty());
            given(likeRepository.save(any(Like.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            boolean created = likeService.like(1L, 2L);

            // assert
            assertThat(created).isTrue();
            verify(likeRepository).save(any(Like.class));
        }

        @DisplayName("이미 등록된 관계면, 저장하지 않고 멱등하게 처리한다.")
        @Test
        void doesNotSaveAgain_whenRelationAlreadyExists() {
            // arrange
            given(likeRepository.findByUserIdAndProductId(1L, 2L))
                .willReturn(Optional.of(new Like(1L, 2L)));

            // act
            boolean created = likeService.like(1L, 2L);

            // assert
            assertThat(created).isFalse();
            verify(likeRepository, never()).save(any(Like.class));
        }
    }

    @DisplayName("좋아요를 취소할 때, ")
    @Nested
    class Unlike {
        @DisplayName("본인의 관계면, 해당 관계를 삭제한다.")
        @Test
        void deletesLike_whenRequesterIsOwner() {
            // arrange
            Like like = new Like(1L, 2L);
            given(likeRepository.findByUserIdAndProductId(1L, 2L)).willReturn(Optional.of(like));

            // act
            likeService.unlike(1L, 2L);

            // assert
            verify(likeRepository).delete(like);
        }

        @DisplayName("관계가 없으면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenRelationIsAbsent() {
            // arrange
            given(likeRepository.findByUserIdAndProductId(1L, 2L)).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                likeService.unlike(1L, 2L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("다른 사용자의 관계는 조회되지 않아, 취소되지 않는다.")
        @Test
        void doesNotDelete_whenRelationBelongsToAnotherUser() {
            // arrange
            given(likeRepository.findByUserIdAndProductId(9L, 2L)).willReturn(Optional.empty());

            // act
            assertThrows(CoreException.class, () -> {
                likeService.unlike(9L, 2L);
            });

            // assert
            verify(likeRepository, never()).delete(any(Like.class));
        }
    }

    @DisplayName("내 좋아요 목록을 조회할 때, ")
    @Nested
    class GetLikes {
        @DisplayName("본인의 관계만 조회한다.")
        @Test
        void returnsOwnLikesOnly() {
            // arrange
            given(likeRepository.findAllByUserId(1L)).willReturn(List.of(new Like(1L, 2L)));

            // act
            List<Like> result = likeService.getLikes(1L);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(1L);
            verify(likeRepository).findAllByUserId(1L);
        }
    }

    @DisplayName("상품의 좋아요 수를 집계할 때, ")
    @Nested
    class CountByProduct {
        @DisplayName("관계 테이블에서 집계한 수를 반환한다.")
        @Test
        void returnsCountFromRelations() {
            // arrange
            given(likeRepository.countByProductId(2L)).willReturn(3L);

            // act
            long result = likeService.countByProductId(2L);

            // assert
            assertThat(result).isEqualTo(3L);
        }
    }
}
