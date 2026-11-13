package com.loopers.domain.user.repository;

import com.loopers.domain.user.entity.User;

public interface UserRepository {
    User save(User user);

    boolean exists(Long userId);

    long count();

    /** 실험용 사용자를 묶음 insert 로 만든다 (8주차 D-167) */
    void createUsers(int count);
}
