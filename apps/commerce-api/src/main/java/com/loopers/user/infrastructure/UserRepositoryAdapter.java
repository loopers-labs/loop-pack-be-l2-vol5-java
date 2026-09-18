package com.loopers.user.infrastructure;

import com.loopers.user.domain.User;
import com.loopers.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public UserRepositoryAdapter(UserJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public User save(User user) {
        if (user.getId() == 0L) {
            entityManager.persist(user);
            return user;
        }
        return jpaRepository.save(user);
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id);
    }
}
