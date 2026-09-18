package com.loopers.application.user;

import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserIdentityRepository;
import com.loopers.domain.user.UserRole;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UserResolver {

    private final UserIdentityRepository userIdentityRepository;

    public UserResolver(UserIdentityRepository userIdentityRepository) {
        this.userIdentityRepository = userIdentityRepository;
    }

    public UserIdentity resolve(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new UserResolutionException(UserResolutionException.Reason.INVALID_USER_ID);
        }
        return userIdentityRepository.findByExternalId(externalId)
            .orElseThrow(() -> new UserResolutionException(UserResolutionException.Reason.USER_NOT_FOUND));
    }

    public UserIdentity requireAdmin(String externalId) {
        UserIdentity identity = resolve(externalId);
        if (identity.role() != UserRole.ADMIN) {
            throw new UserResolutionException(UserResolutionException.Reason.ADMIN_REQUIRED);
        }
        return identity;
    }

    public Optional<String> findExternalId(long userId) {
        return userIdentityRepository.findExternalId(userId);
    }
}
