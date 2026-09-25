package com.loopers.infrastructure.user;

import com.loopers.domain.user.User;

final class UserJpaMapper {

    private UserJpaMapper() {}

    static User toDomain(UserJpaEntity entity) {
        return User.reconstitute(entity.getId());
    }

    static UserJpaEntity toNewEntity() {
        return UserJpaEntity.create();
    }
}
