package com.loopers.user.app.port;

public interface UserRepository {
    boolean existsById(long userId);
}
