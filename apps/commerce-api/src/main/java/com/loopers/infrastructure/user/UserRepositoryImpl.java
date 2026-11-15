package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;

    @Override
    public UserModel save(UserModel user) {
        return userJpaRepository.save(user);
    }

    @Override
    public Optional<UserModel> find(Long id) {
        return userJpaRepository.findById(id);
    }

    @Override
    public Set<Long> findActiveIds(Collection<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(userJpaRepository.findActiveIds(ids));
    }
}
