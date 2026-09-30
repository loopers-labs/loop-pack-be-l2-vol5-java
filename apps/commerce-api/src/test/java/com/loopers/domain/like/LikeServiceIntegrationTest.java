package com.loopers.domain.like;

import com.loopers.infrastructure.like.LikeJpaRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class LikeServiceIntegrationTest {
    @Autowired
    private LikeService likeService;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요를 등록할 때,")
    @Nested
    class Register {
        @DisplayName("처음 등록하면, 저장된다.")
        @Test
        void savesLike_whenFirstRegistration() {
            // act
            likeService.register(1L, 2L);

            // assert
            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, 2L)).isTrue();
        }

        @DisplayName("이미 등록된 조합을 다시 등록하면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflict_whenAlreadyRegistered() {
            // arrange
            likeService.register(1L, 2L);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                likeService.register(1L, 2L)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    class Cancel {
        @DisplayName("등록된 관계가 있으면, 삭제한다.")
        @Test
        void removesLike_whenRelationExists() {
            // arrange
            likeService.register(1L, 2L);

            // act
            likeService.cancel(1L, 2L);

            // assert
            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, 2L)).isFalse();
        }

        @DisplayName("등록된 관계가 없어도, 예외 없이 멱등하게 처리된다.")
        @Test
        void doesNothing_whenRelationDoesNotExist() {
            // act & assert
            likeService.cancel(1L, 2L);

            assertThat(likeJpaRepository.existsByUserIdAndProductId(1L, 2L)).isFalse();
        }
    }

    @DisplayName("내 좋아요 목록을 조회할 때,")
    @Nested
    class GetMyLikes {
        @DisplayName("요청자와 조회 대상 userId가 같으면, 좋아요한 productId 목록을 반환한다.")
        @Test
        void returnsProductIds_whenRequesterMatchesUserId() {
            // arrange
            likeService.register(1L, 2L);
            likeService.register(1L, 3L);

            // act
            List<Long> result = likeService.getMyLikedProductIds(1L, 1L);

            // assert
            assertThat(result).containsExactlyInAnyOrder(2L, 3L);
        }

        @DisplayName("요청자와 조회 대상 userId가 다르면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenRequesterDoesNotMatchUserId() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                likeService.getMyLikedProductIds(1L, 2L)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
