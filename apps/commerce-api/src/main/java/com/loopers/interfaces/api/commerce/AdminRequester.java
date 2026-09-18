package com.loopers.interfaces.api.commerce;

import com.loopers.domain.user.UserRole;
import org.springframework.security.core.Authentication;

public final class AdminRequester {
    private AdminRequester() {
    }

    public static UserRole role(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
            && authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            return UserRole.ADMIN;
        }
        return UserRole.CUSTOMER;
    }
}
