package com.loopers.infrastructure.persistence.shopping.entity;

import com.loopers.domain.shopping.model.User;
import org.springframework.stereotype.Component;

@Component
// User 도메인-엔티티 변환기
public class UserEntityMapper {

    public UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(user.getId());
    }

    public User toDomain(UserJpaEntity entity) {
        return User.restore(entity.getId());
    }
}
