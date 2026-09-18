package com.loopers.domain.like;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeTest {
    @DisplayName("좋아요를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("사용자와 상품이 모두 주어지면, 정상적으로 생성된다.")
        @Test
        void createsLike_whenUserIdAndProductIdAreProvided() {
            // act
            Like like = new Like(1L, 2L);

            // assert
            assertThat(like.getUserId()).isEqualTo(1L);
            assertThat(like.getProductId()).isEqualTo(2L);
        }

        @DisplayName("사용자 식별자가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenUserIdIsMissing() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Like(null, 2L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("상품 식별자가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenProductIdIsMissing() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Like(1L, null);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("좋아요 중복을 판별할 때, ")
    @Nested
    class Duplication {
        @DisplayName("사용자와 상품이 모두 같으면, 같은 좋아요로 판별된다.")
        @Test
        void identifiesAsSame_whenUserIdAndProductIdAreEqual() {
            // assert
            assertThat(new Like(1L, 2L)).isEqualTo(new Like(1L, 2L));
        }

        @DisplayName("사용자나 상품이 다르면, 다른 좋아요로 판별된다.")
        @Test
        void identifiesAsDifferent_whenUserIdOrProductIdDiffers() {
            // assert
            assertThat(new Like(1L, 2L)).isNotEqualTo(new Like(1L, 3L));
            assertThat(new Like(1L, 2L)).isNotEqualTo(new Like(9L, 2L));
        }
    }

    @DisplayName("좋아요 소유를 판별할 때, ")
    @Nested
    class Ownership {
        @DisplayName("요청자가 좋아요의 주인이면, 본인 것으로 판별된다.")
        @Test
        void identifiesAsOwner_whenRequesterIsOwner() {
            // assert
            assertThat(new Like(1L, 2L).isOwnedBy(1L)).isTrue();
        }

        @DisplayName("요청자가 좋아요의 주인이 아니면, 본인 것이 아닌 것으로 판별된다.")
        @Test
        void identifiesAsNotOwner_whenRequesterIsNotOwner() {
            // assert
            assertThat(new Like(1L, 2L).isOwnedBy(9L)).isFalse();
        }
    }
}
