package com.loopers.domain.shopping.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    @DisplayName("양수 ID로 사용자를 생성한다")
    @Test
    void createsUser_whenIdIsPositive() {
        User user = User.create(1L);

        assertThat(user.getId()).isEqualTo(1L);
    }

    @DisplayName("저장된 양수 ID로 사용자를 복원한다")
    @Test
    void restoresUser_whenIdIsPositive() {
        User user = User.restore(2L);

        assertThat(user.getId()).isEqualTo(2L);
    }

    @DisplayName("0 이하 ID로 사용자를 생성할 수 없다")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsCreate_whenIdIsNotPositive(long id) {
        assertInvalidUserId(() -> User.create(id));
    }

    @DisplayName("0 이하 ID로 사용자를 복원할 수 없다")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsRestore_whenIdIsNotPositive(long id) {
        assertInvalidUserId(() -> User.restore(id));
    }

    private void assertInvalidUserId(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(DomainException.class)
            .extracting("errorCode")
            .isEqualTo(DomainErrorCode.INVALID_USER_ID);
    }
}
