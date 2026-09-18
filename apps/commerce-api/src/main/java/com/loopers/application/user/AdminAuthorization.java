package com.loopers.application.user;

import com.loopers.domain.user.UserRole;

public final class AdminAuthorization {
    private AdminAuthorization() {
    }

    public static void requireAdmin(UserRole role) {
        if (role != UserRole.ADMIN) {
            throw new UserResolutionException(UserResolutionException.Reason.ADMIN_REQUIRED);
        }
    }
}
