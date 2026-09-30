package com.loopers.domain.shopping.repository;

import com.loopers.domain.shopping.model.User;

// 사용자 저장소 인터페이스
public interface UserRepository {
    User save(User user);
}
