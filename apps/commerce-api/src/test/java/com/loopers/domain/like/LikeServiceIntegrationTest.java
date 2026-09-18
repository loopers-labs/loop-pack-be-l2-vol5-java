package com.loopers.domain.like;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LikeServiceIntegrationTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요를 등록할 때, ")
    @Nested
    class Like {
        @DisplayName("처음 등록하면, 좋아요가 생성된다.")
        @Test
        void createsLike_whenFirstRequested() {
            // act
            likeService.like(1L, 10L);

            // assert
            assertThat(likeService.countActiveByProduct(10L)).isEqualTo(1L);
        }

        @DisplayName("이미 좋아요한 상품에 다시 요청해도, 중복 생성되지 않는다.")
        @Test
        void doesNotDuplicate_whenAlreadyLiked() {
            // arrange
            likeService.like(1L, 10L);

            // act
            likeService.like(1L, 10L);

            // assert
            assertThat(likeService.countActiveByProduct(10L)).isEqualTo(1L);
        }

        @DisplayName("취소했던 좋아요를 다시 요청하면, 되살아난다.")
        @Test
        void restoresLike_whenLikedAgainAfterCancelled() {
            // arrange
            likeService.like(1L, 10L);
            likeService.unlike(1L, 10L);

            // act
            likeService.like(1L, 10L);

            // assert
            assertThat(likeService.countActiveByProduct(10L)).isEqualTo(1L);
        }
    }

    @DisplayName("좋아요를 취소할 때, ")
    @Nested
    class Unlike {
        @DisplayName("좋아요한 상품을 취소하면, 개수에서 제외된다.")
        @Test
        void removesLike_whenLiked() {
            // arrange
            likeService.like(1L, 10L);

            // act
            likeService.unlike(1L, 10L);

            // assert
            assertThat(likeService.countActiveByProduct(10L)).isEqualTo(0L);
            Optional<LikeModel> result = likeRepository.findByUserIdAndProductId(1L, 10L);
            assertThat(result).isPresent();
            assertThat(result.get().getDeletedAt()).isNotNull();
        }

        @DisplayName("좋아요한 적 없는 상품에 요청해도, 예외 없이 무시된다.")
        @Test
        void doesNothing_whenNeverLiked() {
            // act, assert
            likeService.unlike(1L, 999L);
        }
    }
}
