package com.loopers.application.shopping.query;

import java.util.Optional;

// 사용자 조회용 DAO
public interface UserQueryDao {
    Optional<UserView> findById(long userId);
}
