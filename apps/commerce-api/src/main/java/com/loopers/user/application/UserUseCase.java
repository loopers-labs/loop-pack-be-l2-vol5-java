package com.loopers.user.application;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserUseCase {

    private final UserRepository userRepository;

    public UserUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Long identify(Long userId) {
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            throw new CoreException(ErrorCode.USER_NOT_IDENTIFIED);
        }
        return userId;
    }
}
