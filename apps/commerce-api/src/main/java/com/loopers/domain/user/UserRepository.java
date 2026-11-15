package com.loopers.domain.user;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface UserRepository {
    UserModel save(UserModel user);

    Optional<UserModel> find(Long id);

    /** 주어진 ID 중 삭제되지 않은 사용자 ID. 한 번의 조회로 확인한다. */
    Set<Long> findActiveIds(Collection<Long> ids);
}
