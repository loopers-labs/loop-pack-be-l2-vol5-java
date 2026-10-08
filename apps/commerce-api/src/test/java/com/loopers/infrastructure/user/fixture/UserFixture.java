package com.loopers.infrastructure.user.fixture;

import com.loopers.infrastructure.user.UserJpaEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class UserFixture {
    @PersistenceContext private EntityManager entityManager;

    public void createUser(long userId) {
        entityManager.persist(new UserJpaEntity(userId));
    }
}
