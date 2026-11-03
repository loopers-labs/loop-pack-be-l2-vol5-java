package com.loopers.domains.user.app.port;

public interface UserRepository {
    boolean existsById(long userId);
}
