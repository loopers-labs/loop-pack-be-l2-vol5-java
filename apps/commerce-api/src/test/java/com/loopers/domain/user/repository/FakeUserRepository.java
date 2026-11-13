package com.loopers.domain.user.repository;

import com.loopers.domain.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

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

    @Override
    public void createUsers(int count) {
        for (int i = 0; i < count; i++) {
            save(new User());
        }
    }
}
