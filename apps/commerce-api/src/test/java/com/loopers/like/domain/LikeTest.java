package com.loopers.like.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeTest {

    @DisplayName("[R-LIKE-01] 좋아요는 어떤 고객이 어떤 상품을 좋아요했는지 기록한다.")
    @Nested
    class CreateLike {

        @DisplayName("[동등 클래스 분할] 고객과 상품 식별자를 저장한다.")
        @Test
        void storesUserAndProduct() {
            Like like = new Like(1L, 2L);

            assertAll(
                () -> assertThat(like.getUserId()).isEqualTo(1L),
                () -> assertThat(like.getProductId()).isEqualTo(2L)
            );
        }
    }

    @DisplayName("[R-LIKE-04] 고객은 자신의 좋아요 관계만 취소할 수 있다.")
    @Nested
    class CancelOwnedLike {

        @DisplayName("[의사결정표] 소유자가 취소하면 허용한다.")
        @Test
        void allowsCancel_whenRequesterOwnsLike() {
            Like like = new Like(1L, 2L);

            assertDoesNotThrow(() -> like.cancel(1L));
        }

        @DisplayName("[의사결정표] 다른 고객이 취소하면 없는 좋아요로 거절한다.")
        @Test
        void throwsLikeNotFound_whenRequesterDoesNotOwnLike() {
            Like like = new Like(1L, 2L);

            CoreException result = assertThrows(CoreException.class, () -> like.cancel(3L));

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.LIKE_NOT_FOUND);
        }
    }
}
