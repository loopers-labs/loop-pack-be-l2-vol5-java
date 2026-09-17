package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자 식별(ER-01)과 관리자 판정(ER-02, INV-15). 모든 Facade 의 공통 사전 조건 (설계 5-6).
 */
@RequiredArgsConstructor
@Component
public class UserService {

    private final UserRepository userRepository;

    /** ASM-01: 헤더 값이 가리키는 사용자가 존재할 때만 요청자로 식별된다. */
    public UserModel getUser(Long userId) {
        return userRepository.find(userId)
            .orElseThrow(() -> new CoreException(ErrorType.USER_NOT_FOUND, "[id = " + userId + "] 사용자를 찾을 수 없습니다."));
    }

    /** ASM-26: 식별된 사용자가 관리자 권한을 가진 경우에만 관리자다 (INV-15). */
    public UserModel getAdmin(Long userId) {
        UserModel user = getUser(userId);
        if (!user.isAdmin()) {
            throw new CoreException(ErrorType.NOT_ADMIN, "[id = " + userId + "] 관리자가 아닙니다.");
        }
        return user;
    }
}
