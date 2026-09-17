package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BC-01 사용자. 모든 FR 의 공통 사전 조건 (ER-01 USER_NOT_FOUND, ER-02 NOT_ADMIN, INV-15).
 */
@SpringBootTest
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[공통] 존재하는 사용자 ID 로 요청자를 식별한다.")
    @Test
    void getUser_returnsUser_whenExists() {
        UserModel saved = userRepository.save(new UserModel(false));

        UserModel found = userService.getUser(saved.getId());

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.isAdmin()).isFalse();
    }

    @DisplayName("[공통 USER_NOT_FOUND] 존재하지 않는 사용자 ID 는 USER_NOT_FOUND.")
    @Test
    void getUser_throwsUserNotFound_whenMissing() {
        assertThatThrownBy(() -> userService.getUser(999L))
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(ErrorType.USER_NOT_FOUND);
    }

    @DisplayName("[공통 USER_NOT_FOUND] null ID 도 USER_NOT_FOUND (DR-01: 누락·형식 오류·없음을 나누지 않는다).")
    @Test
    void getUser_throwsUserNotFound_whenNull() {
        assertThatThrownBy(() -> userService.getUser(null))
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(ErrorType.USER_NOT_FOUND);
    }

    @DisplayName("[INV-15] 관리자 권한을 가진 사용자만 관리자로 식별된다.")
    @Test
    void getAdmin_returnsUser_whenAdmin() {
        UserModel admin = userRepository.save(new UserModel(true));

        UserModel found = userService.getAdmin(admin.getId());

        assertThat(found.isAdmin()).isTrue();
    }

    @DisplayName("[공통 NOT_ADMIN][INV-15] 사용자는 있지만 관리자가 아니면 NOT_ADMIN.")
    @Test
    void getAdmin_throwsNotAdmin_whenNotAdmin() {
        UserModel user = userRepository.save(new UserModel(false));

        assertThatThrownBy(() -> userService.getAdmin(user.getId()))
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(ErrorType.NOT_ADMIN);
    }

    @DisplayName("[공통 USER_NOT_FOUND] 관리자 판정에서도 없는 사용자는 USER_NOT_FOUND 가 NOT_ADMIN 보다 먼저다.")
    @Test
    void getAdmin_throwsUserNotFound_whenMissing() {
        assertThatThrownBy(() -> userService.getAdmin(999L))
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(ErrorType.USER_NOT_FOUND);
    }
}
