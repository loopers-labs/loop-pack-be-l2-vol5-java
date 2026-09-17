package com.loopers.domain.like;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeTest {

    @DisplayName("좋아요를 생성할 때, (LIK-001)")
    @Nested
    class Create {

        @DisplayName("사용자 ID와 상품 ID가 있으면, 사용자–상품 관계로 생성된다.")
        @Test
        void createsLike_whenUserIdAndProductIdAreGiven() {
            // act
            Like like = new Like(1L, 2L);

            // assert
            assertAll(
                () -> assertThat(like.getUserId()).isEqualTo(1L),
                () -> assertThat(like.getProductId()).isEqualTo(2L)
            );
        }

        @DisplayName("사용자 ID가 없으면, INVALID_VALUE 예외가 발생한다.")
        @Test
        void throwsInvalidValue_whenUserIdIsNull() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Like(null, 2L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("상품 ID가 없으면, INVALID_VALUE 예외가 발생한다.")
        @Test
        void throwsInvalidValue_whenProductIdIsNull() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Like(1L, null));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }
    }
}
