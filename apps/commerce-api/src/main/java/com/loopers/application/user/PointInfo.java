package com.loopers.application.user;

import com.loopers.domain.user.UserModel;

public record PointInfo(Long userId, long balance) {
    public static PointInfo from(UserModel model) {
        return new PointInfo(model.getId(), model.getPoint().getBalance());
    }
}
