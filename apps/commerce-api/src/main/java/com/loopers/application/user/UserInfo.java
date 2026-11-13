package com.loopers.application.user;

import com.loopers.domain.user.UserModel;

public record UserInfo(Long userId, boolean admin) {
    public static UserInfo from(UserModel user) {
        return new UserInfo(user.getId(), user.isAdmin());
    }
}
