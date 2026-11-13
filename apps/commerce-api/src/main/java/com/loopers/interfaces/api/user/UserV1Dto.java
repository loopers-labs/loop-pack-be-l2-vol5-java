package com.loopers.interfaces.api.user;

import com.loopers.application.user.UserInfo;

public class UserV1Dto {
    /** 본문이 없거나 admin 이 없으면 일반 사용자. */
    public record CreateRequest(Boolean admin) {
    }

    public record UserResponse(Long userId, boolean admin) {
        public static UserResponse from(UserInfo info) {
            return new UserResponse(info.userId(), info.admin());
        }
    }
}
