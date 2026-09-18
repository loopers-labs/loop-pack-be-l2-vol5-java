package com.loopers.domain.like;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeModelTest {

    @DisplayName("좋아요를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("사용자id와 상품id가 주어지면, 정상적으로 생성된다.")
        @Test
        void createsLike_whenValuesAreValid() {
            // act
            LikeModel like = new LikeModel(1L, 2L);

            // assert
            assertThat(like.getUserId()).isEqualTo(1L);
            assertThat(like.getProductId()).isEqualTo(2L);
        }

        @DisplayName("사용자id가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenUserIdIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new LikeModel(null, 2L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("상품id가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenProductIdIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new LikeModel(1L, null));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
