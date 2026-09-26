package com.loopers.user.application;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.User;
import com.loopers.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointUseCase {

    private final UserRepository userRepository;

    public PointUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public long charge(Long userId, long amount) {
        User user = findUser(userId);
        user.charge(amount);
        return userRepository.save(user).getPoint().balance();
    }

    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return findUser(userId).getPoint().balance();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new CoreException(ErrorCode.USER_NOT_IDENTIFIED));
    }
}
