package com.loopers.domains.user.out;

import com.loopers.domains.user.app.port.UserRepository;
import org.springframework.stereotype.Repository;

@Repository
public class UserPersistenceAdapter implements UserRepository {
    private final UserJpaRepository repository;

    public UserPersistenceAdapter(UserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsById(long userId) {
        return repository.existsById(userId);
    }
}
