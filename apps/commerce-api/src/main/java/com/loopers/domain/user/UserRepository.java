package com.loopers.domain.user;

public interface UserRepository {

    boolean existsById(Long userId);

    User save(User user);
}
