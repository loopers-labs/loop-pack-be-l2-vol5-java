package com.loopers.infrastructure.user;

import com.loopers.application.user.UserLookup;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserLookupImpl implements UserLookup {
    private final EntityManager entityManager;

    @Override
    public boolean exists(long userId) {
        return entityManager.find(UserJpaEntity.class, userId) != null;
    }
}
