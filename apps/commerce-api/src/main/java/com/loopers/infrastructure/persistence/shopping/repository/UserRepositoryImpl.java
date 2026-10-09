package com.loopers.infrastructure.persistence.shopping.repository;

import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.infrastructure.persistence.shopping.entity.UserEntityMapper;
import com.loopers.infrastructure.persistence.shopping.entity.UserJpaEntity;
import com.loopers.infrastructure.persistence.shopping.jpa.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// UserRepository JPA 구현체
public class UserRepositoryImpl implements UserRepository {
    private final UserJpaRepository userJpaRepository;
    private final UserEntityMapper userEntityMapper;

    // 사용자 저장
    @Override
    public User save(User user) {
        UserJpaEntity savedEntity = userJpaRepository.save(userEntityMapper.toEntity(user));
        return userEntityMapper.toDomain(savedEntity);
    }
}
