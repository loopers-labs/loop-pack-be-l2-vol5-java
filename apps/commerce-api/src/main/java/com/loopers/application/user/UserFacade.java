package com.loopers.application.user;

import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class UserFacade {
    private final UserRepository userRepository;

    /**
     * USR-01: 요청자로 식별할 사용자가 존재하는지 확인한다. 없으면 UNAUTHORIZED.
     */
    @Transactional(readOnly = true)
    public void checkExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED);
        }
    }
}
