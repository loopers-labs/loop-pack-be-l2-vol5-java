package com.loopers.fixtures;

import com.loopers.domain.shopping.model.User;

public final class UserFixture {
    private UserFixture() {}

    public static User user(long id) {
        return User.create(id);
    }

    public static User firstUser() {
        return user(1L);
    }

    public static User secondUser() {
        return user(2L);
    }
}
