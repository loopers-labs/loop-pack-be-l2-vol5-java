package com.loopers.infrastructure.user;

import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class UserRepositoryImpl implements UserRepository {
    private final UserJpaRepository userJpaRepository;

    @Override
    public User save(User user) {
        return userJpaRepository.save(user);
    }

    @Override
    public boolean exists(Long userId) {
        return userJpaRepository.existsById(userId);
    }

    @Override
    public long count() {
        return userJpaRepository.count();
    }
}
