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
    public boolean existsById(Long userId) {
        return userJpaRepository.existsById(userId);
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = user.getId() == null
            ? UserJpaMapper.toNewEntity()
            : userJpaRepository.findById(user.getId()).orElseThrow(
                () -> new IllegalArgumentException("User does not exist: " + user.getId())
            );
        return UserJpaMapper.toDomain(userJpaRepository.save(entity));
    }
}
