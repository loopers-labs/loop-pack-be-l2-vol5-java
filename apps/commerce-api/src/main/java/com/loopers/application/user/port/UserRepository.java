package com.loopers.application.user.port;

public interface UserRepository {
    boolean existsById(long userId);
}
