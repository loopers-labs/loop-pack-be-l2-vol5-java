package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청자 식별에 필요한 User 존재 여부 확인을 한곳에서 담당한다.
 */
@RequiredArgsConstructor
@Component
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void requireExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.USER_NOT_FOUND);
        }
    }
}
