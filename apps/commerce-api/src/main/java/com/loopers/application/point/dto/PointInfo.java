package com.loopers.application.point.dto;

import com.loopers.domain.user.User;

public record PointInfo(long balance) {
    public static PointInfo from(User user) {
        return new PointInfo(user.getPointBalance().balance().amount());
    }
}
