package com.loopers.domain.user;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** DB 없이 요청자 확인을 검증하기 위한 저장 구현. */
public class FakeUserRepository implements UserRepository {

    private final Map<Long, User> users = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public User save(User user) {
        if (user.getId() == null || user.getId() == 0L) {
            ReflectionTestUtils.setField(user, "id", ++sequence);
        }
        users.put(user.getId(), user);
        return user;
    }

    @Override
    public boolean exists(Long userId) {
        return users.containsKey(userId);
    }

    @Override
    public long count() {
        return users.size();
    }
}
